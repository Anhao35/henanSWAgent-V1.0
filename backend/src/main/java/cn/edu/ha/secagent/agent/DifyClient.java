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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class DifyClient {
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public DifyResult streamChat(String externalUserId, String query, String conversationId, Consumer<DifyEvent> consumer) {
        var config = properties.dify();
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DIFY_NOT_CONFIGURED", "Agent服务尚未配置");
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("inputs", Map.of());
        payload.put("query", query);
        payload.put("response_mode", "streaming");
        payload.put("user", externalUserId);
        if (conversationId != null && !conversationId.isBlank()) payload.put("conversation_id", conversationId);

        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl().replaceAll("/$", "") + "/chat-messages"))
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() / 100 != 2) {
                var detail = response.body().limit(1).findFirst().orElse("");
                throw new ApiException(HttpStatus.BAD_GATEWAY, "DIFY_ERROR", "Dify返回异常：" + response.statusCode() + " " + detail);
            }

            var answer = new StringBuilder();
            var result = new MutableResult();
            response.body().forEach(line -> {
                if (!line.startsWith("data:")) return;
                var data = line.substring(5).trim();
                if (data.isBlank() || "[DONE]".equals(data)) return;
                try {
                    JsonNode event = objectMapper.readTree(data);
                    result.conversationId = text(event, "conversation_id", result.conversationId);
                    result.messageId = text(event, "message_id", result.messageId);
                    result.taskId = text(event, "task_id", result.taskId);
                    var eventName = event.path("event").asText();
                    if (("message".equals(eventName) || "agent_message".equals(eventName)) && event.hasNonNull("answer")) {
                        var delta = event.path("answer").asText();
                        answer.append(delta);
                        consumer.accept(new DifyEvent("append", delta, result.conversationId));
                    } else if ("workflow_finished".equals(eventName)) {
                        var workflowAnswer = findWorkflowAnswer(event.path("data").path("outputs"));
                        if (!workflowAnswer.isBlank()) {
                            answer.setLength(0);
                            answer.append(workflowAnswer);
                            consumer.accept(new DifyEvent("replace", answer.toString(), result.conversationId));
                        }
                    } else if ("error".equals(eventName)) {
                        throw new DifyStreamException(event.path("message").asText("Dify执行失败"));
                    } else if (eventName.endsWith("started") || eventName.endsWith("finished")) {
                        consumer.accept(new DifyEvent("status", describe(eventName), result.conversationId));
                    }
                } catch (DifyStreamException exception) {
                    throw exception;
                } catch (Exception ignored) {
                    // 忽略无法识别的非关键流事件，继续读取后续消息。
                }
            });
            return new DifyResult(answer.toString(), result.conversationId, result.messageId, result.taskId);
        } catch (DifyStreamException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DIFY_ERROR", exception.getMessage());
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DIFY_UNAVAILABLE", "Agent服务暂不可用：" + exception.getMessage());
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        return node.hasNonNull(field) ? node.path(field).asText() : fallback;
    }

    private static String findWorkflowAnswer(JsonNode outputs) {
        if (!outputs.isObject()) return "";
        for (String key : new String[]{"answer", "final_output", "result", "text", "output"}) {
            if (outputs.hasNonNull(key) && outputs.path(key).isValueNode()) return outputs.path(key).asText();
        }
        return "";
    }

    private static String describe(String event) {
        if (event.contains("workflow")) return event.endsWith("started") ? "Agent工作流已启动" : "Agent工作流已完成";
        if (event.contains("node")) return event.endsWith("started") ? "正在执行分析节点" : "分析节点执行完成";
        return "Agent正在处理";
    }

    public record DifyEvent(String type, String content, String conversationId) {}
    public record DifyResult(String answer, String conversationId, String messageId, String taskId) {}
    private static final class MutableResult { String conversationId = ""; String messageId = ""; String taskId = ""; }
    private static final class DifyStreamException extends RuntimeException { DifyStreamException(String message) { super(message); } }
}
