package cn.edu.ha.secagent.repository;

import cn.edu.ha.secagent.domain.Conversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    Page<Conversation> findByUserIdAndDeletedAtIsNullOrderByLastMessageAtDescCreatedAtDesc(UUID userId, Pageable pageable);
    Optional<Conversation> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);
}

