package cn.edu.ha.secagent.repository;

import cn.edu.ha.secagent.domain.ResearchSearch;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ResearchSearchRepository extends JpaRepository<ResearchSearch, UUID> {
    List<ResearchSearch> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
