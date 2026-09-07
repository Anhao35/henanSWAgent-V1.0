package cn.edu.ha.secagent.repository;

import cn.edu.ha.secagent.domain.MitreMappingRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MitreMappingRunRepository extends JpaRepository<MitreMappingRun, UUID> {
    List<MitreMappingRun> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<MitreMappingRun> findFirstByUserIdAndMappingTypeAndInputHashAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
            UUID userId, String mappingType, String inputHash, String status, LocalDateTime createdAfter);

    Optional<MitreMappingRun> findByIdAndUserId(UUID id, UUID userId);
}
