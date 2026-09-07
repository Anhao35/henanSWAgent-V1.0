package cn.edu.ha.secagent.auth;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.config.AppProperties;
import cn.edu.ha.secagent.domain.Organization;
import cn.edu.ha.secagent.domain.User;
import cn.edu.ha.secagent.domain.UserProfile;
import cn.edu.ha.secagent.domain.UserRole;
import cn.edu.ha.secagent.domain.UserStatus;
import cn.edu.ha.secagent.repository.OrganizationRepository;
import cn.edu.ha.secagent.repository.UserProfileRepository;
import cn.edu.ha.secagent.repository.UserRepository;
import cn.edu.ha.secagent.user.UserView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final OrganizationRepository organizationRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;
    private final VerificationCodeService verificationCodeService;

    @Transactional
    public UserView register(AuthDtos.RegisterRequest request) {
        var username = request.username().trim().toLowerCase(Locale.ROOT);
        boolean verifyEmail = "EMAIL".equalsIgnoreCase(request.verificationChannel());
        var email = verifyEmail ? normalizeNullable(request.email()) : null;
        var phone = verifyEmail ? null : normalizeNullable(request.phone());
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

        String verifiedTarget = verifyEmail ? email : phone;
        if (verifiedTarget == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VERIFICATION_TARGET_REQUIRED",
                    verifyEmail ? "请填写需要验证的邮箱" : "请填写需要验证的手机号");
        }
        verificationCodeService.verifyRegistration(request.verificationChannel(), verifiedTarget, request.verificationCode());

        var user = new User();
        user.setOrganization(organization);
        user.setUsername(username);
        user.setDisplayName(request.displayName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setEmail(email);
        user.setPhone(phone);
        user.setEmailVerified(verifyEmail);
        user.setPhoneVerified(!verifyEmail);
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
    public VerificationCodeService.SendResult createPasswordReset(String account, String channel) {
        return verificationCodeService.send(new AuthDtos.SendVerificationCodeRequest(
                "RESET_PASSWORD", channel, account));
    }

    @Transactional
    public void resetPassword(String account, String channel, String code, String newPassword) {
        var user = userRepository.findForLogin(account.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_CODE", "验证码错误、已过期或已使用"));
        verificationCodeService.verifyPasswordReset(account, channel, code);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
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
}
