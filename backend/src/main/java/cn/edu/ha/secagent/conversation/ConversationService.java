package cn.edu.ha.secagent.conversation;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.domain.ChatMessage;
import cn.edu.ha.secagent.domain.Conversation;
import cn.edu.ha.secagent.repository.ChatMessageRepository;
import cn.edu.ha.secagent.repository.ConversationRepository;
import cn.edu.ha.secagent.repository.MessageAttachmentRepository;
import cn.edu.ha.secagent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversationService {
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final MessageAttachmentRepository attachmentRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<ConversationDtos.ConversationView> list(UUID userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        return conversationRepository.findByUserIdAndDeletedAtIsNullOrderByLastMessageAtDescCreatedAtDesc(
                        userId, PageRequest.of(Math.max(page, 0), safeSize))
                .map(ConversationDtos.ConversationView::of).getContent();
    }

    @Transactional
    public ConversationDtos.ConversationView create(UUID userId, String requestedTitle) {
        var user = userRepository.findDetailedById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        var conversation = new Conversation();
        conversation.setUser(user);
        conversation.setOrganization(user.getOrganization());
        conversation.setTitle(requestedTitle == null || requestedTitle.isBlank() ? "新会话" : requestedTitle.trim());
        return ConversationDtos.ConversationView.of(conversationRepository.save(conversation));
    }

    @Transactional(readOnly = true)
    public List<ConversationDtos.MessageView> messages(UUID userId, UUID conversationId) {
        requireOwned(userId, conversationId);
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)
                .stream().map(message -> ConversationDtos.MessageView.of(message,
                        attachmentRepository.findByMessageIdOrderByCreatedAtAsc(message.getId())
                                .stream().map(AttachmentService.AttachmentView::of).toList())).toList();
    }

    @Transactional
    public ConversationDtos.ConversationView rename(UUID userId, UUID conversationId, String title) {
        var conversation = requireOwned(userId, conversationId);
        conversation.setTitle(title.trim());
        return ConversationDtos.ConversationView.of(conversationRepository.save(conversation));
    }

    @Transactional
    public void delete(UUID userId, UUID conversationId) {
        var conversation = requireOwned(userId, conversationId);
        conversation.setDeletedAt(LocalDateTime.now());
        conversation.setStatus("DELETED");
        conversationRepository.save(conversation);
    }

    @Transactional(readOnly = true)
    public Conversation requireOwned(UUID userId, UUID conversationId) {
        return conversationRepository.findByIdAndUserIdAndDeletedAtIsNull(conversationId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "会话不存在"));
    }

    @Transactional
    public void updateDifyConversation(UUID conversationId, String difyConversationId) {
        if (difyConversationId == null || difyConversationId.isBlank()) return;
        var conversation = conversationRepository.findById(conversationId).orElseThrow();
        if (conversation.getDifyConversationId() == null || conversation.getDifyConversationId().isBlank()) {
            conversation.setDifyConversationId(difyConversationId);
            conversationRepository.save(conversation);
        }
    }

    @Transactional
    public void touchAfterMessage(UUID conversationId, String firstUserMessage) {
        var conversation = conversationRepository.findById(conversationId).orElseThrow();
        if ("新会话".equals(conversation.getTitle())) {
            var compact = firstUserMessage.replaceAll("\\s+", " ").trim();
            conversation.setTitle(compact.length() > 30 ? compact.substring(0, 30) + "…" : compact);
        }
        conversation.setLastMessageAt(LocalDateTime.now());
        conversationRepository.save(conversation);
    }
}
