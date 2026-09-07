package cn.edu.ha.secagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "verification_codes")
public class VerificationCode extends BaseEntity {
    public enum Purpose { REGISTER, RESET_PASSWORD }
    public enum Channel { EMAIL, PHONE }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Purpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Channel channel;

    @Column(nullable = false, length = 128)
    private String subject;

    @Column(nullable = false, length = 128)
    private String destination;

    @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String codeHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private int attemptCount;

    private LocalDateTime consumedAt;
}
