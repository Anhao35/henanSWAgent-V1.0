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
    private final RunTraceService traces;

    @Transactional
    public PreparedQuery prepare(UUID userId, UUID conversationId, String rawType, String rawValue, String requestedId) {
        var conversation = conversationService.requireOwned(userId, conversationId);
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        var type = workflowClient.normalizeType(rawType);
        var value = rawValue == null ? "" : rawValue.trim();
        if (value.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_QUERY", "请输入查询目标");
        var requestId = requestedId == null || requestedId.isBlank() ? UUID.randomUUID().toString() : requestedId;
        traces.guard(conversationId,requestId);
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
        runRepository.saveAndFlush(run);
        traces.initialize(run.getId(),assistantMessage.getId(),"SECURITY");
        conversationService.touchAfterMessage(conversationId, display);

        return new PreparedQuery(conversationId, assistantMessage.getId(), run.getId(), type, value,
                "hnsec_" + userId, requestId, run.getStartedAt());
    }

    public void executeStream(PreparedQuery prepared, OutputStream output) {
        try(var channel=new RunChannel(traces,prepared.runId(),prepared.assistantMessageId(),"SECURITY",output)) {
          try {
            var result = workflowClient.run(prepared.type(), prepared.value(), prepared.externalUserId(),prepared.runId(),channel::event);
            complete(prepared, result);
            String status=result.partial()?"PARTIAL":"COMPLETED";
            traces.terminal(prepared.runId(),status);
            channel.stage("finished",result.partial()?"查询结束，部分来源失败":"查询完成",result.partial()?"WARNING":"COMPLETED");
            channel.send(Map.of("type", "done", "answer", result.answer(),"status",status,
                    "requestId", prepared.requestId()));
        } catch (Exception exception) {
            var message = exception instanceof ApiException ? exception.getMessage() : "快捷查询执行失败";
            fail(prepared, message);
            String status=traces.cancelled(prepared.runId())?"CANCELLED":"FAILED";
            traces.terminal(prepared.runId(),status);
            channel.stage("finished",message,status);
            channel.send(Map.of("type", "error", "error", message,"status",status,
                    "requestId", prepared.requestId()));
          }
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
