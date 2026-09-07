package cn.edu.ha.secagent.repository;

import cn.edu.ha.secagent.domain.ResearchSavedItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResearchSavedItemRepository extends JpaRepository<ResearchSavedItem, UUID> {
    List<ResearchSavedItem> findByUserIdOrderByCreatedAtDesc(UUID userId);
    Optional<ResearchSavedItem> findByIdAndUserId(UUID id, UUID userId);
    Optional<ResearchSavedItem> findByUserIdAndSourceAndSourceId(UUID userId, String source, String sourceId);
}
