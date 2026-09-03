package cn.edu.ha.secagent.agent;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.config.AppProperties;
import cn.edu.ha.secagent.conversation.AttachmentService;
import cn.edu.ha.secagent.storage.ObjectStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class DifyClient {
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectStorageService storageService;
    private final AttachmentTextExtractor attachmentTextExtractor;

    public DifyResult streamChat(String externalUserId, String query, String conversationId,
                                 List<AttachmentService.AttachmentContent> attachments,
                                 Consumer<DifyEvent> consumer) {
        var config = properties.dify();
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DIFY_NOT_CONFIGURED", "Agent服务尚未配置");
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("inputs", Map.of());
        payload.put("query", attachmentTextExtractor.enrich(query, attachments));
        payload.put("response_mode", "streaming");
        payload.put("user", externalUserId);
        if (conversationId != null && !conversationId.isBlank()) payload.put("conversation_id", conversationId);

        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            if (attachments != null && !attachments.isEmpty()) {
                var files = new ArrayList<Map<String, String>>();
                for (var attachment : attachments) {
                    if (!attachment.contentType().startsWith("image/")) continue;
                    var uploadId = uploadFile(client, externalUserId, attachment);
                    files.add(Map.of(
                            "type", difyFileType(attachment.contentType()),
                            "transfer_method", "local_file",
                            "upload_file_id", uploadId
                    ));
                }
                if (!files.isEmpty()) payload.put("files", files);
            }
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
                        if ("failed".equalsIgnoreCase(event.path("data").path("status").asText())) {
                            throw new DifyStreamException(event.path("data").path("error").asText("Dify工作流执行失败"));
                        }
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

    private String uploadFile(HttpClient client, String externalUserId, AttachmentService.AttachmentContent attachment) throws Exception {
        var stored = storageService.read(attachment.objectKey());
        var boundary = "----HnSecAgent" + UUID.randomUUID().toString().replace("-", "");
        var body = multipart(boundary, externalUserId, attachment.name(), stored.contentType(), stored.bytes());
        var config = properties.dify();
        var request = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl().replaceAll("/$", "") + "/files/upload"))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            var detail = response.body() == null ? "" : response.body();
            if (detail.length() > 300) detail = detail.substring(0, 300);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DIFY_FILE_UPLOAD_ERROR",
                    "附件上传到Dify失败：" + response.statusCode() + " " + detail);
        }
        var id = objectMapper.readTree(response.body()).path("id").asText();
        if (id.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "DIFY_FILE_UPLOAD_ERROR", "Dify未返回附件ID");
        return id;
    }

    private static byte[] multipart(String boundary, String user, String filename, String contentType, byte[] bytes) throws Exception {
        var output = new ByteArrayOutputStream();
        writeUtf8(output, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"user\"\r\n\r\n" + user + "\r\n");
        var safeFilename = filename.replace("\r", "").replace("\n", "").replace("\"", "_");
        writeUtf8(output, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + safeFilename
                + "\"\r\nContent-Type: " + contentType + "\r\n\r\n");
        output.write(bytes);
        writeUtf8(output, "\r\n--" + boundary + "--\r\n");
        return output.toByteArray();
    }

    private static void writeUtf8(ByteArrayOutputStream output, String value) throws Exception {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String difyFileType(String contentType) {
        if (contentType.startsWith("image/")) return "image";
        if (contentType.startsWith("audio/")) return "audio";
        if (contentType.startsWith("video/")) return "video";
        return "document";
    }

    public record DifyEvent(String type, String content, String conversationId) {}
    public record DifyResult(String answer, String conversationId, String messageId, String taskId) {}
    private static final class MutableResult { String conversationId = ""; String messageId = ""; String taskId = ""; }
    private static final class DifyStreamException extends RuntimeException { DifyStreamException(String message) { super(message); } }
}
