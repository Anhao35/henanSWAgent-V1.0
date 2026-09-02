package cn.edu.ha.secagent.repository;

import cn.edu.ha.secagent.domain.AgentRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {}

