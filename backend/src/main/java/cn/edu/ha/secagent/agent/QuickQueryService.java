package cn.edu.ha.secagent.agent;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.conversation.ConversationService;
import cn.edu.ha.secagent.domain.AgentRun;
import cn.edu.ha.secagent.domain.ChatMessage;
import cn.edu.ha.secagent.repository.AgentRunRepository;
import cn.edu.ha.secagent.repository.ChatMessageRepository;
import cn.edu.ha.secagent.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class QuickQueryService {
    private final ConversationService conversationService;
    private final ChatMessageRepository messageRepository;
    private final AgentRunRepository runRepository;
    private final UserRepository userRepository;
    private final DifyWorkflowClient workflowClient;
    private final ObjectMapper objectMapper;

    @Transactional
    public PreparedQuery prepare(UUID userId, UUID conversationId, String rawType, String rawValue, String requestedId) {
        var conversation = conversationService.requireOwned(userId, conversationId);
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        var type = workflowClient.normalizeType(rawType);
        var value = rawValue == null ? "" : rawValue.trim();
        if (value.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_QUERY", "请输入查询目标");
        var requestId = requestedId == null || requestedId.isBlank() ? UUID.randomUUID().toString() : requestedId;
        var display = workflowClient.label(type) + "：" + value;

        var userMessage = new ChatMessage();
        userMessage.setConversation(conversation);
        userMessage.setRole("USER");
        userMessage.setContent(display);
        userMessage.setStatus("COMPLETED");
        userMessage.setRequestId(requestId);
        messageRepository.save(userMessage);

        var assistantMessage = new ChatMessage();
        assistantMessage.setConversation(conversation);
        assistantMessage.setRole("ASSISTANT");
        assistantMessage.setContent("");
        assistantMessage.setStatus("STREAMING");
        assistantMessage.setRequestId(requestId);
        messageRepository.save(assistantMessage);

        var run = new AgentRun();
        run.setConversation(conversation);
        run.setUser(user);
        run.setRequestId(requestId);
        run.setRunType("QUICK_QUERY");
        run.setTargetType(type.toUpperCase());
        run.setStatus("RUNNING");
        run.setStartedAt(LocalDateTime.now());
        runRepository.save(run);
        conversationService.touchAfterMessage(conversationId, display);

        return new PreparedQuery(conversationId, assistantMessage.getId(), run.getId(), type, value,
                "hnsec_" + userId, requestId, run.getStartedAt());
    }

    public void executeStream(PreparedQuery prepared, OutputStream output) {
        var connected = new AtomicBoolean(true);
        writeSafely(output, Map.of("type", "status", "message", "正在直连对应安全子工作流",
                "requestId", prepared.requestId()), connected);
        try {
            var result = workflowClient.run(prepared.type(), prepared.value(), prepared.externalUserId(), status -> {
                var payload = new LinkedHashMap<String, Object>();
                payload.put("type", "status");
                payload.put("message", status);
                payload.put("requestId", prepared.requestId());
                writeSafely(output, payload, connected);
            });
            complete(prepared, result);
            writeSafely(output, Map.of("type", "done", "answer", result.answer(),
                    "requestId", prepared.requestId()), connected);
        } catch (Exception exception) {
            var message = exception instanceof ApiException ? exception.getMessage() : "快捷查询执行失败";
            fail(prepared, message);
            writeSafely(output, Map.of("type", "error", "error", message,
                    "requestId", prepared.requestId()), connected);
        }
    }

    @Transactional
    public void complete(PreparedQuery prepared, DifyWorkflowClient.WorkflowResult result) {
        var message = messageRepository.findById(prepared.assistantMessageId()).orElseThrow();
        message.setContent(result.answer());
        message.setStatus("COMPLETED");
        messageRepository.save(message);

        var run = runRepository.findById(prepared.runId()).orElseThrow();
        run.setDifyTaskId(blankToNull(result.taskId()));
        run.setDifyWorkflowRunId(blankToNull(result.workflowRunId()));
        run.setStatus("COMPLETED");
        run.setFinishedAt(LocalDateTime.now());
        run.setLatencyMs(Duration.between(prepared.startedAt(), run.getFinishedAt()).toMillis());
        runRepository.save(run);
    }

    @Transactional
    public void fail(PreparedQuery prepared, String error) {
        messageRepository.findById(prepared.assistantMessageId()).ifPresent(message -> {
            message.setContent("快捷查询执行失败，请稍后重试。");
            message.setStatus("FAILED");
            message.setErrorMessage(limit(error));
            messageRepository.save(message);
        });
        runRepository.findById(prepared.runId()).ifPresent(run -> {
            run.setStatus("FAILED");
            run.setErrorMessage(limit(error));
            run.setFinishedAt(LocalDateTime.now());
            run.setLatencyMs(Duration.between(prepared.startedAt(), run.getFinishedAt()).toMillis());
            runRepository.save(run);
        });
    }

    private void writeSafely(OutputStream output, Map<String, ?> payload, AtomicBoolean connected) {
        if (!connected.get()) return;
        try {
            synchronized (output) {
                output.write((objectMapper.writeValueAsString(payload) + "\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (Exception ignored) {
            connected.set(false);
        }
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static String limit(String value) {
        if (value == null) return null;
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    public record PreparedQuery(UUID conversationId, UUID assistantMessageId, UUID runId, String type,
                                String value, String externalUserId, String requestId, LocalDateTime startedAt) {}
}
