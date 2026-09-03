package cn.edu.ha.secagent.conversation;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.domain.ChatMessage;
import cn.edu.ha.secagent.domain.MessageAttachment;
import cn.edu.ha.secagent.repository.MessageAttachmentRepository;
import cn.edu.ha.secagent.repository.UserRepository;
import cn.edu.ha.secagent.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AttachmentService {
    private static final int MAX_ATTACHMENTS_PER_MESSAGE = 3;
    private final ConversationService conversationService;
    private final UserRepository userRepository;
    private final MessageAttachmentRepository attachmentRepository;
    private final ObjectStorageService storageService;

    @Transactional
    public AttachmentView upload(UUID userId, UUID conversationId, MultipartFile file) {
        var conversation = conversationService.requireOwned(userId, conversationId);
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        var stored = storageService.saveAttachment(userId, file);
        var attachment = new MessageAttachment();
        attachment.setConversation(conversation);
        attachment.setUser(user);
        attachment.setObjectKey(stored.objectKey());
        attachment.setOriginalName(stored.originalName());
        attachment.setContentType(stored.contentType());
        attachment.setSizeBytes(stored.sizeBytes());
        attachment.setStatus("UPLOADED");
        return AttachmentView.of(attachmentRepository.save(attachment));
    }

    @Transactional(readOnly = true)
    public ObjectStorageService.StoredObject read(UUID userId, UUID conversationId, UUID attachmentId) {
        var attachment = requireOwned(userId, conversationId, attachmentId);
        return storageService.read(attachment.getObjectKey());
    }

    @Transactional
    public void delete(UUID userId, UUID conversationId, UUID attachmentId) {
        var attachment = requireOwned(userId, conversationId, attachmentId);
        if (attachment.getMessage() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_ALREADY_USED", "已发送的附件不能删除");
        }
        storageService.delete(attachment.getObjectKey());
        attachmentRepository.delete(attachment);
    }

    @Transactional
    public List<AttachmentContent> bind(UUID userId, UUID conversationId, ChatMessage message, List<UUID> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) return List.of();
        var ids = new ArrayList<>(new LinkedHashSet<>(requestedIds));
        if (ids.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOO_MANY_ATTACHMENTS", "每条消息最多上传3个附件");
        }
        var result = new ArrayList<AttachmentContent>();
        for (var id : ids) {
            var attachment = requireOwned(userId, conversationId, id);
            if (attachment.getMessage() != null) {
                throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_ALREADY_USED", "附件已用于其他消息，请重新上传");
            }
            attachment.setMessage(message);
            attachment.setStatus("ATTACHED");
            attachmentRepository.save(attachment);
            result.add(new AttachmentContent(
                    attachment.getId(), attachment.getObjectKey(), attachment.getOriginalName(),
                    attachment.getContentType(), attachment.getSizeBytes()
            ));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<AttachmentView> forMessage(UUID messageId) {
        return attachmentRepository.findByMessageIdOrderByCreatedAtAsc(messageId)
                .stream().map(AttachmentView::of).toList();
    }

    private MessageAttachment requireOwned(UUID userId, UUID conversationId, UUID attachmentId) {
        return attachmentRepository.findByIdAndConversationIdAndUserId(attachmentId, conversationId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "附件不存在"));
    }

    public record AttachmentView(UUID id, String name, String contentType, long sizeBytes, String downloadUrl) {
        static AttachmentView of(MessageAttachment entity) {
            return new AttachmentView(entity.getId(), entity.getOriginalName(), entity.getContentType(), entity.getSizeBytes(),
                    "/api/conversations/" + entity.getConversation().getId() + "/attachments/" + entity.getId());
        }
    }

    public record AttachmentContent(UUID id, String objectKey, String name, String contentType, long sizeBytes) {}
}
