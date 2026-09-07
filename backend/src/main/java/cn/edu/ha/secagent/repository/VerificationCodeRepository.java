package cn.edu.ha.secagent.repository;

import cn.edu.ha.secagent.domain.VerificationCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;

public interface VerificationCodeRepository extends JpaRepository<VerificationCode, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<VerificationCode> findFirstByPurposeAndChannelAndSubjectOrderByCreatedAtDesc(
            VerificationCode.Purpose purpose,
            VerificationCode.Channel channel,
            String subject
    );

    long deleteByExpiresAtBefore(LocalDateTime cutoff);
}
