package cn.edu.ha.secagent.auth;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.config.AppProperties;
import cn.edu.ha.secagent.domain.Organization;
import cn.edu.ha.secagent.domain.PasswordResetToken;
import cn.edu.ha.secagent.domain.User;
import cn.edu.ha.secagent.domain.UserProfile;
import cn.edu.ha.secagent.domain.UserRole;
import cn.edu.ha.secagent.domain.UserStatus;
import cn.edu.ha.secagent.repository.OrganizationRepository;
import cn.edu.ha.secagent.repository.PasswordResetTokenRepository;
import cn.edu.ha.secagent.repository.UserProfileRepository;
import cn.edu.ha.secagent.repository.UserRepository;
import cn.edu.ha.secagent.user.UserView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final OrganizationRepository organizationRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final AppProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public UserView register(AuthDtos.RegisterRequest request) {
        var username = request.username().trim().toLowerCase(Locale.ROOT);
        var email = normalizeNullable(request.email());
        var phone = normalizeNullable(request.phone());
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已被使用");
        }
        if (email != null && userRepository.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "邮箱已被使用");
        }
        if (phone != null && userRepository.existsByPhone(phone)) {
            throw new ApiException(HttpStatus.CONFLICT, "PHONE_EXISTS", "手机号已被使用");
        }
        if (email == null && phone == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONTACT_REQUIRED", "邮箱和手机号至少填写一项");
        }

        var orgCode = normalizeNullable(request.organizationCode());
        Organization organization = organizationRepository.findByCode(orgCode == null ? "HERCERT" : orgCode)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "ORGANIZATION_NOT_FOUND", "所属组织不存在"));

        var user = new User();
        user.setOrganization(organization);
        user.setUsername(username);
        user.setDisplayName(request.displayName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setEmail(email);
        user.setPhone(phone);
        user.setRole(UserRole.ANALYST);
        user.setStatus(properties.registration().requireApproval() ? UserStatus.PENDING : UserStatus.ACTIVE);
        user = userRepository.save(user);

        var profile = new UserProfile();
        profile.setUser(user);
        profileRepository.save(profile);
        return UserView.of(user, profile);
    }

    @Transactional(readOnly = true)
    public UserView getUser(UUID userId) {
        var user = userRepository.findDetailedById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        return UserView.of(user, profileRepository.findById(userId).orElse(null));
    }

    @Transactional
    public void recordLogin(UUID userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setLastLoginAt(LocalDateTime.now());
            userRepository.save(user);
        });
    }

    @Transactional
    public Optional<String> createPasswordReset(String account) {
        var user = userRepository.findForLogin(account.trim()).orElse(null);
        if (user == null || user.getStatus() == UserStatus.DISABLED) return Optional.empty();

        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        var rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash(sha256(rawToken));
        token.setExpiresAt(LocalDateTime.now().plusMinutes(properties.passwordReset().ttlMinutes()));
        resetTokenRepository.save(token);

        if (user.getEmail() != null) {
            try {
                var mail = new SimpleMailMessage();
                mail.setTo(user.getEmail());
                mail.setSubject("河南省教育科研网安全智能体密码重置");
                mail.setText("请在有效期内打开以下链接重置密码：\n" +
                        properties.passwordReset().publicBaseUrl() + "/reset-password?token=" + rawToken);
                mailSender.send(mail);
            } catch (RuntimeException ignored) {
                // 开发环境邮件服务未启动时，仍允许使用开发令牌完成联调。
            }
        }
        return properties.passwordReset().exposeTokenInDev() ? Optional.of(rawToken) : Optional.empty();
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        var token = resetTokenRepository.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESET_TOKEN", "重置链接无效或已过期"));
        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESET_TOKEN", "重置链接无效或已过期");
        }
        token.getUser().setPasswordHash(passwordEncoder.encode(newPassword));
        token.setUsedAt(LocalDateTime.now());
        userRepository.save(token.getUser());
        resetTokenRepository.save(token);
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INVALID", "当前密码不正确");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

