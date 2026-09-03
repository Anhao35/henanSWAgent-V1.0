package cn.edu.ha.secagent.repository;

import cn.edu.ha.secagent.domain.MessageAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageAttachmentRepository extends JpaRepository<MessageAttachment, UUID> {
    Optional<MessageAttachment> findByIdAndConversationIdAndUserId(UUID id, UUID conversationId, UUID userId);
    List<MessageAttachment> findByMessageIdOrderByCreatedAtAsc(UUID messageId);
}
