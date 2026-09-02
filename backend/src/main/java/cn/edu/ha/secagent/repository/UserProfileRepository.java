package cn.edu.ha.secagent.repository;

import cn.edu.ha.secagent.domain.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {}

