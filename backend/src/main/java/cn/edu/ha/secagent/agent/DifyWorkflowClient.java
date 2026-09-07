package cn.edu.ha.secagent.agent;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.config.AppProperties;
import cn.edu.ha.secagent.evidence.EvidenceGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class DifyWorkflowClient {
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final DifyStreamTransport transport;
    private final RunTraceService traces;
    private final EvidenceGateway evidenceGateway;

    public WorkflowResult run(String type, String value, String externalUserId, java.util.UUID runId, Consumer<DifyClient.DifyEvent> statusConsumer) {
        var normalizedValue = evidenceGateway.normalize(type, value);
        var targets = targets(type).stream()
                .filter(target -> target.apiKey() != null && !target.apiKey().isBlank())
                .toList();

        var results = new ArrayList<SingleWorkflowResult>();
        var sources = new ArrayList<Map<String,Object>>();
        statusConsumer.accept(DifyClient.progress("统一证据网关", "正在并行查询公开情报源", "RUNNING"));
        var evidenceBundle = evidenceGateway.query(type, normalizedValue);
        for (var item : evidenceBundle.sources()) {
            var source = new LinkedHashMap<String, Object>();
            source.put("source", item.source());
            source.put("query_success", item.querySuccess());
            source.put("found", item.found());
            source.put("verdict", item.verdict());
            source.put("summary", item.summary());
            source.put("source_url", item.sourceUrl());
            source.put("attributes", item.attributes());
            if (item.errorCode() != null) source.put("error_code", item.errorCode());
            sources.add(source);
        }
        statusConsumer.accept(DifyClient.progress("统一证据网关",
                "公开源查询完成：成功 " + evidenceBundle.successfulSources() + "，失败 " + evidenceBundle.failedSources(),
                evidenceBundle.partial() ? "WARNING" : "COMPLETED"));
        for (var target : targets) {
            if(traces.cancelled(runId)) throw new ApiException(HttpStatus.CONFLICT,"RUN_CANCELLED","已停止查询；已提交的第三方分析可能继续执行");
            statusConsumer.accept(DifyClient.progress(target.name(),"正在直连" + target.name(),"RUNNING"));
            try {
                var result=call(target, type, normalizedValue, externalUserId,runId,statusConsumer);
                results.add(result);
                sources.add(Map.of("source",target.name(),"query_success",true,"partial",result.partial(),"answer",result.answer()));
                statusConsumer.accept(DifyClient.progress(target.name(),target.name()+(result.partial()?"部分步骤成功":"查询完成"),result.partial()?"PARTIAL":"COMPLETED"));
            } catch(ApiException e) {
                if(traces.cancelled(runId)) throw e;
                sources.add(Map.of("source",target.name(),"query_success",false,"error",e.getMessage()));
                statusConsumer.accept(DifyClient.progress(target.name(),target.name()+"查询失败，继续保留其他来源结果","WARNING"));
            }
        }
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("schema_version", evidenceBundle.schemaVersion());
        snapshot.put("type", type);
        snapshot.put("target", normalizedValue);
        snapshot.put("queried_at", evidenceBundle.queriedAt().toString());
        snapshot.put("sources", sources);
        traces.evidence(runId, snapshot);
        if(results.isEmpty() && evidenceBundle.successfulSources() == 0)
            throw new ApiException(HttpStatus.BAD_GATEWAY,"ALL_SOURCES_FAILED","所有已配置情报源均查询失败，不能视为未检出或安全");

        var answer = new StringBuilder("# ").append(label(type)).append("结果\n\n")
                .append("查询目标：`").append(normalizedValue.replace("`", "\\`")).append("`\n\n")
                .append(evidenceGateway.renderMarkdown(evidenceBundle)).append("\n");
        for (var result : results) {
            answer.append("\n## ").append(result.name()).append("\n\n").append(result.answer()).append("\n");
        }
        for(var source:sources) if(Boolean.FALSE.equals(source.get("query_success"))) answer.append("\n> ").append(source.get("source")).append("：查询失败，不能作为安全结论的依据。\n");
        var first = results.isEmpty() ? null : results.get(0);
        boolean partial = evidenceBundle.partial() || results.size() < targets.size() || results.stream().anyMatch(SingleWorkflowResult::partial);
        return new WorkflowResult(answer.toString().trim(), first == null ? null : first.taskId(),
                first == null ? null : first.workflowRunId(), partial);
    }

    public String normalizeType(String rawType) {
        var type = rawType == null ? "" : rawType.trim().toLowerCase();
        if (!List.of("ip", "domain", "url", "cve", "hash").contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY_TYPE", "不支持的快捷查询类型");
        }
        return type;
    }

    public String label(String type) {
        return switch (type) {
            case "ip" -> "IP查询";
            case "domain" -> "域名查询";
            case "url" -> "URL查询";
            case "cve" -> "CVE查询";
            case "hash" -> "Hash查询";
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY_TYPE", "不支持的快捷查询类型");
        };
    }

    private List<WorkflowTarget> targets(String type) {
        var keys = properties.dify().workflowKeys();
        return switch (type) {
            case "ip" -> List.of(
                    new WorkflowTarget("VirusTotal IP 情报", keys.get("ip-vt")),
                    new WorkflowTarget("微步信誉 IP 情报", keys.get("ip-weibu"))
            );
            case "domain" -> List.of(new WorkflowTarget("域名情报", keys.get("domain")));
            case "url" -> List.of(new WorkflowTarget("URL 安全检测", keys.get("url")));
            case "cve" -> List.of(new WorkflowTarget("CVE 漏洞情报", keys.get("cve")));
            case "hash" -> List.of(new WorkflowTarget("Hash 威胁情报", keys.get("hash")));
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY_TYPE", "不支持的快捷查询类型");
        };
    }

    private SingleWorkflowResult call(WorkflowTarget target, String type, String value, String externalUserId,java.util.UUID runId,Consumer<DifyClient.DifyEvent> consumer) {
        var config = properties.dify();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("inputs", workflowInputs(type, value));
        payload.put("response_mode", "streaming");
        payload.put("user", externalUserId);

        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl().replaceAll("/$", "") + "/workflows/run"))
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Authorization", "Bearer " + target.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            var result=transport.execute(request,target.apiKey(),externalUserId,true,config.timeoutSeconds(),runId,target.name(),event->{
                if(!event.type().equals("append")&&!event.type().equals("replace")) consumer.accept(event);
            });
            var answer=result.answer();
            if (answer.isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "DIFY_WORKFLOW_EMPTY",
                        target.name() + "已执行，但没有返回可展示的结果");
            }
            return new SingleWorkflowResult(target.name(), answer.trim(), result.taskId(),result.workflowRunId(),result.partial());
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DIFY_WORKFLOW_UNAVAILABLE",
                    target.name() + "暂不可用，请检查后端配置与上游连接");
        }
    }

    private Map<String, String> workflowInputs(String type, String value) {
        var inputs = new LinkedHashMap<String, String>();
        for (var key : List.of("input", "query", "resource", "target", "indicator", "ioc", "value")) {
            inputs.put(key, value);
        }
        inputs.put("type", type);
        inputs.put("ip", "ip".equals(type) ? value : "");
        inputs.put("domain", "domain".equals(type) ? value : "");
        inputs.put("url", "url".equals(type) ? value : "");
        inputs.put("cve", "cve".equals(type) ? value : "");
        inputs.put("hash", "hash".equals(type) ? value : "");
        inputs.put("keyword", "cve".equals(type) ? value : "");
        return inputs;
    }

    private String extractAnswer(JsonNode outputs) {
        if (!outputs.isObject()) return "";
        for (var key : List.of("answer", "text", "result", "output", "content")) {
            var value = outputs.path(key);
            if (value.isTextual() && !value.asText().isBlank()) return value.asText();
        }
        var values = new ArrayList<String>();
        outputs.fields().forEachRemaining(entry -> {
            if (entry.getValue().isTextual() && !entry.getValue().asText().isBlank()) {
                values.add("- **" + entry.getKey() + "**：" + entry.getValue().asText());
            }
        });
        return String.join("\n", values);
    }

    private static String limit(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) : value;
    }

    private record WorkflowTarget(String name, String apiKey) {}
    private record SingleWorkflowResult(String name, String answer, String taskId, String workflowRunId,boolean partial) {}
    public record WorkflowResult(String answer, String taskId, String workflowRunId,boolean partial) {}
}
