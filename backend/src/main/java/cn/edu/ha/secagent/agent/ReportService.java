package cn.edu.ha.secagent.agent;

import cn.edu.ha.secagent.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReportService {
    private final JdbcTemplate jdbc;
    private final RunTraceService traces;
    private final ObjectMapper mapper;
    private static final Set<String> SECTIONS=Set.of("scope","answer","evidence","remediation","limitations");

    public List<Map<String,Object>> templates() {
        return jdbc.query("SELECT * FROM report_templates ORDER BY template_key,version DESC",(rs,n)->Map.of("id",rs.getString("id"),"key",rs.getString("template_key"),"version",rs.getInt("version"),"name",rs.getString("name"),"sections",tree(rs.getString("sections_json"))));
    }

    @Transactional
    public Map<String,Object> publish(String key,String name,List<String> sections) {
        if(key==null||!key.matches("[a-z][a-z0-9_-]{1,47}")||sections==null||sections.isEmpty()||!SECTIONS.containsAll(sections)) throw bad("模板类型或章节无效");
        var unique=new LinkedHashSet<>(sections); unique.add("evidence");unique.add("limitations");
        jdbc.queryForList("SELECT id FROM report_templates WHERE template_key=? FOR UPDATE",key);
        int version=jdbc.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM report_templates WHERE template_key=?",Integer.class,key);
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO report_templates(id,template_key,version,name,sections_json) VALUES (?,?,?,?,?)",id,key,version,name,traces.json(unique));
        return Map.of("id",id,"version",version);
    }

    public List<Map<String,Object>> list(UUID user) {
        return jdbc.query("SELECT s.id,s.root_id,s.version,s.title,s.status,s.created_at,t.name FROM saved_reports s JOIN report_templates t ON t.id=s.template_id JOIN agent_runs r ON r.id=s.run_id JOIN conversations c ON c.id=r.conversation_id WHERE s.user_id=? AND c.deleted_at IS NULL ORDER BY s.created_at DESC LIMIT 200",(rs,n)->Map.of("id",rs.getString("id"),"rootId",rs.getString("root_id"),"version",rs.getInt("version"),"title",rs.getString("title"),"status",rs.getString("status"),"templateName",rs.getString("name"),"createdAt",rs.getTimestamp("created_at").toInstant().toString()),RunTraceService.bytes(user));
    }

    public Map<String,Object> get(UUID user,String id) {
        var rows=jdbc.query("SELECT s.*,t.name,t.version template_version FROM saved_reports s JOIN report_templates t ON t.id=s.template_id JOIN agent_runs r ON r.id=s.run_id JOIN conversations c ON c.id=r.conversation_id WHERE s.id=? AND s.user_id=? AND c.deleted_at IS NULL",(rs,n)->{
            var m=new LinkedHashMap<String,Object>();m.put("id",id);m.put("rootId",rs.getString("root_id"));m.put("version",rs.getInt("version"));
            m.put("title",rs.getString("title"));m.put("content",rs.getString("content"));m.put("status",rs.getString("status"));
            m.put("snapshot",tree(rs.getString("snapshot_json")));m.put("snapshotSha256",rs.getString("snapshot_sha256"));
            m.put("templateName",rs.getString("name"));m.put("templateVersion",rs.getInt("template_version"));return m;
        },id,RunTraceService.bytes(user));
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"REPORT_NOT_FOUND","报告不存在");
        return rows.get(0);
    }

    @Transactional
    public Map<String,Object> generate(UUID user,UUID run,String templateId,String title) {
        traces.requireOwned(user,run);
        var source=jdbc.queryForObject("SELECT r.status,r.run_type,r.started_at,x.task_mode,x.evidence_json,m.content FROM agent_runs r JOIN run_contexts x ON x.run_id=r.id JOIN chat_messages m ON m.id=x.assistant_message_id WHERE r.id=?",(rs,n)->{
            var m=new LinkedHashMap<String,Object>();m.put("runId",run.toString());m.put("status",rs.getString("status"));m.put("mode",rs.getString("task_mode"));
            m.put("runType",rs.getString("run_type"));m.put("queriedAt",rs.getTimestamp("started_at").toInstant().toString());m.put("answer",rs.getString("content"));m.put("evidence",tree(rs.getString("evidence_json")));return m;
        },RunTraceService.bytes(run));
        if(!Set.of("COMPLETED","PARTIAL").contains(source.get("status"))) throw bad("任务尚未成功结束，不能生成正式报告草稿");
        var template=templates().stream().filter(t->t.get("id").equals(templateId)).findFirst().orElseThrow(()->bad("模板不存在"));
        if(Set.of("READ","EXPLAIN").contains(source.get("mode"))&&!template.get("key").equals("detailed")) throw bad("资料解读只支持原文归档，不应使用安全简报或漏洞处置模板");
        String id=UUID.randomUUID().toString();String snapshot=traces.json(source);
        String body=render(title,(JsonNode)template.get("sections"),tree(snapshot));
        jdbc.update("INSERT INTO saved_reports(id,root_id,version,user_id,run_id,template_id,title,content,snapshot_json,snapshot_sha256,status) VALUES (?,?,1,?,?,?,?,?,?,?,'DRAFT')",
            id,id,RunTraceService.bytes(user),RunTraceService.bytes(run),templateId,title,body,snapshot,sha256(snapshot));
        return get(user,id);
    }

    @Transactional
    public Map<String,Object> revise(UUID user,String id,String title,String content,String status) {
        if(!Set.of("DRAFT","FINAL").contains(status)) throw bad("报告状态无效");
        var original=get(user,id); String root=(String)original.get("rootId");
        jdbc.queryForList("SELECT id FROM saved_reports WHERE id=? FOR UPDATE",root);
        int version=jdbc.queryForObject("SELECT MAX(version)+1 FROM saved_reports WHERE root_id=?",Integer.class,root);
        String newId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO saved_reports(id,root_id,version,user_id,run_id,template_id,title,content,snapshot_json,snapshot_sha256,status) SELECT ?,root_id,?,user_id,run_id,template_id,?,?,snapshot_json,snapshot_sha256,? FROM saved_reports WHERE id=? AND user_id=?",
            newId,version,title,content,status,id,RunTraceService.bytes(user));
        return get(user,newId);
    }

    static String render(String title,JsonNode sections,JsonNode snapshot) {
        var out=new StringBuilder("# ").append(title.replaceAll("[\\r\\n]"," ")).append("\n\n");
        var evidence=snapshot.path("evidence");
        for(var section:sections) switch(section.asText()) {
            case "scope" -> out.append("## 本次任务\n\n运行编号：").append(snapshot.path("runId").asText()).append("\n\n证据采集时间：").append(snapshot.path("queriedAt").asText()).append("\n\n执行状态：").append(snapshot.path("status").asText()).append("\n\n");
            case "answer" -> out.append("## 原始分析结果（待人工复核）\n\n").append(snapshot.path("answer").asText()).append("\n\n");
            case "evidence" -> {
                out.append("## 证据快照\n\n");
                if(evidence.isMissingNode()||evidence.isNull()||evidence.isEmpty()) out.append("本次未捕获结构化证据。已有回答仅作为模型分析记录归档，不应视为经过证据校验的安全结论。\n\n");
                else if(evidence.has("sources")) {
                    out.append("查询对象：").append(cell(evidence.path("target").asText())).append("\n\n");
                    for(var item:evidence.path("sources")) {
                        out.append("### ").append(cell(item.path("source").asText())).append("\n\n");
                        if(!item.path("query_success").asBoolean()) {
                            out.append("查询失败，不代表安全或未检出。\n\n");
                        } else if(item.hasNonNull("answer")) {
                            out.append(item.path("answer").asText()).append("\n\n");
                        } else {
                            out.append("状态：").append(item.path("found").asBoolean()?"命中":"未检出")
                                    .append("；判断：").append(cell(item.path("verdict").asText("未返回")))
                                    .append("；摘要：").append(cell(item.path("summary").asText("未返回"))).append("\n\n");
                            if(item.path("attributes").isObject()&&!item.path("attributes").isEmpty())
                                out.append("关键字段：`").append(cell(item.path("attributes").toString())).append("`\n\n");
                        }
                    }
                } else {
                    out.append("总体风险（上游原值）：").append(cell(evidence.path("f_unified_risk_level").asText("未返回"))).append("\n\n有效证据数（上游原值）：").append(cell(evidence.path("f_unified_evidence_count").asText("未返回"))).append("\n\n");
                    var items=evidence.path("f_unified_ioc_list_json");
                    try { if(items.isTextual()) items=new ObjectMapper().readTree(items.asText()); }
                    catch(Exception ignored) { items=null; }
                    if(items!=null && items.isArray() && !items.isEmpty()) {
                        out.append("| 类型 | 对象 | 主体风险 | 上下文风险 | 最终风险 | 证据数 |\n|---|---|---|---|---|---|\n");
                        for(var item:items) {
                            out.append("| ");
                            for(String field:List.of("ioc_type","ioc","subject_risk_level","context_risk_level","final_risk_level","evidence_count")) out.append(cell(item.path(field).asText("未返回"))).append(" | ");
                            out.append("\n");
                        }
                        out.append("\n");
                    }
                    out.append("完整结构化证据随报告版本保存，可下载快照 JSON。风险值由上游证据融合提供，本模板不重新计算。\n\n");
                }
            }
            case "remediation" -> out.append("## 处置核验清单（尚未执行）\n\n- 受影响资产与版本：待资产负责人核验。\n- 补丁与缓解措施：依据厂商公告和本次证据逐项确认。\n- 责任人、期限与复测结果：待人工填写。\n\n本节是待办清单，不表示已发现受影响资产或已完成修复。\n\n");
            case "limitations" -> out.append("## 使用范围与限制\n\n- 本文复用上述运行的证据快照，未重新查询外部情报。\n- 查询失败、字段缺失、未检出与安全结论不得混同。\n- 原始分析结果可能包含模型推断，关键判断仍需人工复核。\n- 编辑报告不会改变原始证据；保存将生成新版本。\n\n");
            default -> throw new IllegalArgumentException("Unknown report section");
        }
        return out.toString();
    }
    static String cell(String value) { return value.replace("|","\\|").replace("\n"," ").replace("\r"," "); }
    static String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    private JsonNode tree(String text) { try { return text==null?mapper.nullNode():mapper.readTree(text); } catch(Exception e) { throw bad("已保存的数据格式异常"); } }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"REPORT_INVALID",message); }
}
