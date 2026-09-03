package cn.edu.ha.secagent.agent;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.config.AppProperties;
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

    public WorkflowResult run(String type, String value, String externalUserId, Consumer<String> statusConsumer) {
        var targets = targets(type).stream()
                .filter(target -> target.apiKey() != null && !target.apiKey().isBlank())
                .toList();
        if (targets.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WORKFLOW_NOT_CONFIGURED",
                    label(type) + "尚未配置可用的 Dify Workflow API Key");
        }

        var results = new ArrayList<SingleWorkflowResult>();
        for (var target : targets) {
            statusConsumer.accept("正在调用" + target.name() + "子工作流");
            results.add(call(target, type, value, externalUserId));
        }

        var answer = new StringBuilder("# ").append(label(type)).append("结果\n\n")
                .append("查询目标：`").append(value.replace("`", "\\`")).append("`\n");
        for (var result : results) {
            answer.append("\n## ").append(result.name()).append("\n\n").append(result.answer()).append("\n");
        }
        var first = results.get(0);
        return new WorkflowResult(answer.toString().trim(), first.taskId(), first.workflowRunId());
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

    private SingleWorkflowResult call(WorkflowTarget target, String type, String value, String externalUserId) {
        var config = properties.dify();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("inputs", workflowInputs(type, value));
        payload.put("response_mode", "blocking");
        payload.put("user", externalUserId);

        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl().replaceAll("/$", "") + "/workflows/run"))
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Authorization", "Bearer " + target.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "DIFY_WORKFLOW_ERROR",
                        target.name() + "返回异常：" + response.statusCode() + " " + limit(response.body(), 300));
            }
            var body = objectMapper.readTree(response.body());
            var data = body.path("data");
            if ("failed".equalsIgnoreCase(data.path("status").asText())) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "DIFY_WORKFLOW_ERROR",
                        target.name() + "执行失败：" + data.path("error").asText("未知错误"));
            }
            var answer = extractAnswer(data.path("outputs"));
            if (answer.isBlank()) answer = extractAnswer(body.path("outputs"));
            if (answer.isBlank() && body.path("answer").isTextual()) answer = body.path("answer").asText();
            if (answer.isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "DIFY_WORKFLOW_EMPTY",
                        target.name() + "已执行，但没有返回可展示的结果");
            }
            return new SingleWorkflowResult(target.name(), answer.trim(), body.path("task_id").asText(),
                    body.path("workflow_run_id").asText(data.path("id").asText()));
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DIFY_WORKFLOW_UNAVAILABLE",
                    target.name() + "暂不可用：" + exception.getMessage());
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
    private record SingleWorkflowResult(String name, String answer, String taskId, String workflowRunId) {}
    public record WorkflowResult(String answer, String taskId, String workflowRunId) {}
}
