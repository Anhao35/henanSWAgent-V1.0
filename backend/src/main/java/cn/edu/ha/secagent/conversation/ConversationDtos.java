package cn.edu.ha.secagent.conversation;

import cn.edu.ha.secagent.domain.ChatMessage;
import cn.edu.ha.secagent.domain.Conversation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class ConversationDtos {
    private ConversationDtos() {}

    public record CreateConversationRequest(@Size(max = 160) String title) {}
    public record RenameConversationRequest(@NotBlank @Size(max = 160) String title) {}
    public record SendMessageRequest(@Size(max = 20000) String message, List<UUID> attachmentIds, @Size(max=32) String taskMode) {}
    public record IocQueryRequest(@NotBlank String type, @NotBlank @Size(max = 4096) String value) {}

    public record ConversationView(UUID id, String title, String status, LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime lastMessageAt) {
        public static ConversationView of(Conversation entity) {
            return new ConversationView(entity.getId(), entity.getTitle(), entity.getStatus(), entity.getCreatedAt(), entity.getUpdatedAt(), entity.getLastMessageAt());
        }
    }

    public record MessageView(UUID id, String role, String content, String status, String errorMessage,
                              LocalDateTime createdAt, List<AttachmentService.AttachmentView> attachments) {
        public static MessageView of(ChatMessage entity, List<AttachmentService.AttachmentView> attachments) {
            return new MessageView(entity.getId(), entity.getRole(), entity.getContent(), entity.getStatus(),
                    entity.getErrorMessage(), entity.getCreatedAt(), attachments);
        }
    }
}
