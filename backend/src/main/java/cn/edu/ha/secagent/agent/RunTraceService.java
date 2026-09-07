package cn.edu.ha.secagent.agent;

import cn.edu.ha.secagent.common.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.ByteBuffer;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RunTraceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    // Called inside prepare's transaction: serialize concurrent submissions for one conversation.
    public void guard(UUID conversation, String requestId) {
        if (requestId.length() > 64 || !requestId.matches("[A-Za-z0-9_-]+"))
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_ID", "请求编号格式错误");
        jdbc.queryForList("SELECT id FROM conversations WHERE id=? FOR UPDATE", bytes(conversation));
        if (jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs WHERE request_id=?", Integer.class, requestId) > 0)
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "此请求已提交，请查看会话运行记录，不要重复执行");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs WHERE conversation_id=? AND status IN ('RUNNING','CANCEL_REQUESTED')", Integer.class, bytes(conversation)) > 0)
            throw new ApiException(HttpStatus.CONFLICT, "RUN_IN_PROGRESS", "此会话仍有任务执行中，请等待完成或停止任务");
    }

    public void initialize(UUID run, UUID message, String mode) {
        jdbc.update("INSERT INTO run_contexts(run_id,assistant_message_id,task_mode) VALUES (?,?,?)", bytes(run), bytes(message), mode);
    }

    public String modeKey(String mode,String key) {
        try { return mode+":"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,20); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public String conversation(UUID conversation,String key) {
        var values=jdbc.queryForList("SELECT dify_conversation_id FROM dify_mode_conversations WHERE conversation_id=? AND mode_key=?",String.class,bytes(conversation),key);
        return values.isEmpty()?"":values.get(0);
    }
    public void conversation(UUID conversation,String key,String upstream) {
        if(upstream==null||upstream.isBlank()) return;
        jdbc.update("INSERT INTO dify_mode_conversations(conversation_id,mode_key,dify_conversation_id) VALUES (?,?,?) ON DUPLICATE KEY UPDATE dify_conversation_id=VALUES(dify_conversation_id)",bytes(conversation),key,upstream);
    }
    public void terminal(UUID run,String status) {
        jdbc.update("UPDATE agent_runs SET status=?,finished_at=NOW(6),latency_ms=TIMESTAMPDIFF(MICROSECOND,started_at,NOW(6))/1000 WHERE id=?",status,bytes(run));
        jdbc.update("UPDATE chat_messages m JOIN run_contexts c ON c.assistant_message_id=m.id SET m.status=? WHERE c.run_id=?",status,bytes(run));
    }

    public Map<String,Object> append(UUID run, Map<String,Object> details) {
        var event = new LinkedHashMap<String,Object>(details);
        event.put("type", "trace"); event.put("runId", run.toString()); event.put("timestamp", Instant.now().toString());
        var holder = new GeneratedKeyHolder();
        var json = json(event);
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO run_events(run_id,event_json) VALUES (?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setBytes(1, bytes(run)); statement.setString(2, json); return statement;
        }, holder);
        event.put("sequence", Objects.requireNonNull(holder.getKey()).longValue());
        return event;
    }

    public Map<String,Object> stage(UUID run, String node, String name, String status) {
        return append(run, Map.of("nodeExecutionId", node, "displayName", name, "status", status, "stage", "platform"));
    }

    public void upstream(UUID run, String task, String workflow) {
        if (task != null && !task.isBlank()) jdbc.update("UPDATE agent_runs SET dify_task_id=? WHERE id=?", safe(task,64), bytes(run));
        if (workflow != null && !workflow.isBlank()) jdbc.update("UPDATE agent_runs SET dify_workflow_run_id=? WHERE id=?", safe(workflow,64), bytes(run));
    }

    public void evidence(UUID run, Object value) {
        var json = json(value);
        if (json.length() > 2_000_000) {
            stage(run, "evidence-limit", "证据快照超过保存上限，报告将注明证据不完整", "WARNING");
            return;
        }
        jdbc.update("UPDATE run_contexts SET evidence_json=? WHERE run_id=?", json, bytes(run));
    }

    public void partial(UUID run, String answer) {
        jdbc.update("UPDATE chat_messages m JOIN run_contexts c ON c.assistant_message_id=m.id SET m.content=? WHERE c.run_id=? AND m.status='STREAMING'", answer, bytes(run));
    }

    public boolean cancelled(UUID run) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT cancel_requested FROM run_contexts WHERE run_id=?", Boolean.class, bytes(run)));
    }

    @Transactional
    public Map<String,Object> cancel(UUID user, UUID run) {
        requireOwned(user,run);
        jdbc.queryForList("SELECT id FROM agent_runs WHERE id=? FOR UPDATE", bytes(run));
        jdbc.update("UPDATE run_contexts c JOIN agent_runs r ON r.id=c.run_id SET c.cancel_requested=TRUE, r.status='CANCEL_REQUESTED' WHERE c.run_id=? AND r.status='RUNNING'", bytes(run));
        return Map.of("message", "已提交停止请求；以任务最终状态为准", "runId", run);
    }

    public void requireOwned(UUID user, UUID run) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs r JOIN run_contexts x ON x.run_id=r.id JOIN conversations c ON c.id=r.conversation_id WHERE r.id=? AND r.user_id=? AND c.deleted_at IS NULL", Integer.class, bytes(run), bytes(user)) == 0)
            throw new ApiException(HttpStatus.NOT_FOUND,"RUN_NOT_FOUND","运行记录不存在");
    }

    public List<Map<String,Object>> list(UUID user, UUID conversation) {
        return jdbc.query("SELECT BIN_TO_UUID(r.id) id,BIN_TO_UUID(x.assistant_message_id) message_id,r.status,x.task_mode FROM agent_runs r JOIN run_contexts x ON x.run_id=r.id JOIN conversations c ON c.id=r.conversation_id WHERE r.user_id=? AND r.conversation_id=? AND c.deleted_at IS NULL ORDER BY r.started_at", (rs,n) -> Map.of("id",rs.getString("id"),"messageId",rs.getString("message_id"),"status",rs.getString("status"),"taskMode",rs.getString("task_mode")), bytes(user),bytes(conversation));
    }

    public Map<String,Object> snapshot(UUID user, UUID run, long after) {
        requireOwned(user,run);
        var result = jdbc.queryForObject("SELECT r.status,r.started_at,r.finished_at,r.latency_ms,x.task_mode,BIN_TO_UUID(x.assistant_message_id) message_id,m.content FROM agent_runs r JOIN run_contexts x ON x.run_id=r.id JOIN chat_messages m ON m.id=x.assistant_message_id WHERE r.id=?", (rs,n) -> {
            var m=new LinkedHashMap<String,Object>(); m.put("id",run); m.put("status",rs.getString("status"));
            m.put("startedAt",rs.getTimestamp("started_at").toInstant().toString()); m.put("taskMode",rs.getString("task_mode"));
            m.put("latencyMs",rs.getObject("latency_ms")); m.put("messageId",rs.getString("message_id")); m.put("answer",rs.getString("content")); return m;
        }, bytes(run));
        var events = jdbc.query("SELECT sequence,event_json FROM run_events WHERE run_id=? AND sequence>? ORDER BY sequence LIMIT 500", (rs,n) -> {
            var event=parse(rs.getString("event_json")); event.put("sequence",rs.getLong("sequence")); return event;
        }, bytes(run),Math.max(0,after));
        result.put("events",events); result.put("hasMore",events.size()==500);
        result.put("nextSequence",events.isEmpty()? after:events.get(events.size()-1).get("sequence")); return result;
    }

    public static String safe(String text, int max) { return text == null ? "" : text.substring(0,Math.min(text.length(),max)); }
    public String json(Object value) { try { return mapper.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("无法序列化运行数据",e); } }
    public Map<String,Object> parse(String value) { try { return mapper.readValue(value,new TypeReference<LinkedHashMap<String,Object>>(){}); } catch(Exception e) { throw new IllegalArgumentException("运行记录格式错误",e); } }
}
