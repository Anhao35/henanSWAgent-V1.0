package cn.edu.ha.secagent.agent;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.conversation.AttachmentService;
import cn.edu.ha.secagent.conversation.ConversationService;
import cn.edu.ha.secagent.domain.AgentRun;
import cn.edu.ha.secagent.domain.ChatMessage;
import cn.edu.ha.secagent.repository.AgentRunRepository;
import cn.edu.ha.secagent.repository.ChatMessageRepository;
import cn.edu.ha.secagent.repository.ConversationRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class AgentService {
    private final ConversationService conversationService;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final AgentRunRepository runRepository;
    private final UserRepository userRepository;
    private final AttachmentService attachmentService;
    private final DifyClient difyClient;
    private final ObjectMapper objectMapper;

    @Transactional
    public PreparedRun prepare(UUID userId, UUID conversationId, String content, List<UUID> attachmentIds, String requestedId) {
        var conversation = conversationService.requireOwned(userId, conversationId);
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        var requestId = requestedId == null || requestedId.isBlank() ? UUID.randomUUID().toString() : requestedId;
        var normalizedContent = content == null ? "" : content.trim();
        if (normalizedContent.isBlank() && (attachmentIds == null || attachmentIds.isEmpty())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_MESSAGE", "请输入消息或添加附件");
        }
        if (normalizedContent.isBlank()) normalizedContent = "请分析上传的附件。";

        var userMessage = new ChatMessage();
        userMessage.setConversation(conversation);
        userMessage.setRole("USER");
        userMessage.setContent(normalizedContent);
        userMessage.setStatus("COMPLETED");
        userMessage.setRequestId(requestId);
        messageRepository.save(userMessage);
        var attachments = attachmentService.bind(userId, conversationId, userMessage, attachmentIds);

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
        run.setRunType("CHAT");
        run.setStatus("RUNNING");
        run.setStartedAt(LocalDateTime.now());
        runRepository.save(run);
        conversationService.touchAfterMessage(conversationId, normalizedContent);

        return new PreparedRun(
                conversationId,
                assistantMessage.getId(),
                run.getId(),
                "hnsec_" + userId,
                conversation.getDifyConversationId(),
                normalizedContent,
                attachments,
                requestId,
                run.getStartedAt()
        );
    }

    public void executeStream(PreparedRun prepared, OutputStream output) {
        var clientConnected = new AtomicBoolean(true);
        writeSafely(output, Map.of("type", "status", "message", "请求已保存，正在连接安全智能体", "requestId", prepared.requestId()), clientConnected);
        try {
            var result = difyClient.streamChat(
                    prepared.externalUserId(),
                    prepared.content(),
                    prepared.difyConversationId(),
                    prepared.attachments(),
                    event -> {
                        try {
                            var payload = new LinkedHashMap<String, Object>();
                            payload.put("type", event.type());
                            if ("status".equals(event.type())) payload.put("message", event.content());
                            else payload.put("answer", event.content());
                            if (event.conversationId() != null && !event.conversationId().isBlank()) {
                                payload.put("conversationId", event.conversationId());
                            }
                            writeSafely(output, payload, clientConnected);
                        } catch (Exception ignored) {
                            // 客户端断开不应中止 Dify 执行；最终结果仍需持久化。
                        }
                    });
            complete(prepared, result);
            writeSafely(output, Map.of(
                    "type", "done",
                    "answer", result.answer(),
                    "requestId", prepared.requestId(),
                    "conversationId", result.conversationId() == null ? "" : result.conversationId()
            ), clientConnected);
        } catch (Exception exception) {
            var message = exception instanceof ApiException ? exception.getMessage() : "Agent执行失败";
            fail(prepared, message);
            writeSafely(output, Map.of("type", "error", "error", message, "requestId", prepared.requestId()), clientConnected);
        }
    }

    @Transactional
    public void complete(PreparedRun prepared, DifyClient.DifyResult result) {
        var message = messageRepository.findById(prepared.assistantMessageId()).orElseThrow();
        message.setContent(result.answer() == null || result.answer().isBlank() ? "Agent已完成，但没有返回内容。" : result.answer());
        message.setStatus("COMPLETED");
        message.setDifyMessageId(result.messageId());
        messageRepository.save(message);
        conversationService.updateDifyConversation(prepared.conversationId(), result.conversationId());

        var run = runRepository.findById(prepared.runId()).orElseThrow();
        run.setDifyTaskId(result.taskId());
        run.setStatus("COMPLETED");
        run.setFinishedAt(LocalDateTime.now());
        run.setLatencyMs(Duration.between(prepared.startedAt(), run.getFinishedAt()).toMillis());
        runRepository.save(run);
    }

    @Transactional
    public void fail(PreparedRun prepared, String error) {
        messageRepository.findById(prepared.assistantMessageId()).ifPresent(message -> {
            message.setContent("分析请求执行失败，请稍后重试。");
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

    private void write(OutputStream output, Map<String, ?> payload) throws Exception {
        synchronized (output) {
            output.write((objectMapper.writeValueAsString(payload) + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private void writeSafely(OutputStream output, Map<String, ?> payload, AtomicBoolean clientConnected) {
        if (!clientConnected.get()) return;
        try {
            write(output, payload);
        } catch (Exception ignored) {
            clientConnected.set(false);
        }
    }

    private static String limit(String value) {
        if (value == null) return null;
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    public record PreparedRun(
            UUID conversationId,
            UUID assistantMessageId,
            UUID runId,
            String externalUserId,
            String difyConversationId,
            String content,
            List<AttachmentService.AttachmentContent> attachments,
            String requestId,
            LocalDateTime startedAt
    ) {}

}
