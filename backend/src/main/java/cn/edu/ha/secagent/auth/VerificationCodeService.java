package cn.edu.ha.secagent.auth;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.config.AppProperties;
import cn.edu.ha.secagent.domain.UserStatus;
import cn.edu.ha.secagent.domain.VerificationCode;
import cn.edu.ha.secagent.repository.UserRepository;
import cn.edu.ha.secagent.repository.VerificationCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class VerificationCodeService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");

    private final VerificationCodeRepository codeRepository;
    private final UserRepository userRepository;
    private final JavaMailSender mailSender;
    private final RestClient.Builder restClientBuilder;
    private final AppProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public SendResult send(AuthDtos.SendVerificationCodeRequest request) {
        var purpose = parsePurpose(request.purpose());
        var channel = parseChannel(request.channel());
        var target = normalize(request.target());

        String subject;
        String destination;
        if (purpose == VerificationCode.Purpose.REGISTER) {
            validateDestination(channel, target);
            ensureAvailable(channel, target);
            subject = target;
            destination = target;
        } else {
            var user = userRepository.findForLogin(target).orElse(null);
            if (user == null || user.getStatus() == UserStatus.DISABLED) {
                return SendResult.hidden(properties.verification().ttlMinutes() * 60L);
            }
            destination = channel == VerificationCode.Channel.EMAIL ? user.getEmail() : user.getPhone();
            if (destination == null || destination.isBlank()) {
                return SendResult.hidden(properties.verification().ttlMinutes() * 60L);
            }
            destination = normalize(destination);
            subject = user.getId().toString();
        }

        var previous = codeRepository.findFirstByPurposeAndChannelAndSubjectOrderByCreatedAtDesc(purpose, channel, subject);
        if (previous.isPresent()) {
            long seconds = Duration.between(previous.get().getCreatedAt(), LocalDateTime.now()).getSeconds();
            if (seconds < properties.verification().resendSeconds()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CODE_SEND_TOO_FREQUENT",
                        "验证码发送过于频繁，请稍后再试");
            }
        }

        int digits = channel == VerificationCode.Channel.PHONE ? 4 : 6;
        String rawCode = String.format(Locale.ROOT, "%0" + digits + "d", secureRandom.nextInt((int) Math.pow(10, digits)));
        var code = new VerificationCode();
        code.setPurpose(purpose);
        code.setChannel(channel);
        code.setSubject(subject);
        code.setDestination(destination);
        code.setCodeHash(hash(purpose, channel, subject, rawCode));
        code.setExpiresAt(LocalDateTime.now().plusMinutes(properties.verification().ttlMinutes()));
        codeRepository.saveAndFlush(code);

        deliver(channel, destination, rawCode, purpose);
        return new SendResult(
                "验证码已发送",
                mask(channel, destination),
                properties.verification().ttlMinutes() * 60L,
                properties.verification().exposeCodeInDev() ? rawCode : null
        );
    }

    @Transactional
    public void verifyRegistration(String channelValue, String target, String rawCode) {
        var channel = parseChannel(channelValue);
        var normalized = normalize(target);
        verify(VerificationCode.Purpose.REGISTER, channel, normalized, rawCode);
    }

    @Transactional
    public void verifyPasswordReset(String account, String channelValue, String rawCode) {
        var channel = parseChannel(channelValue);
        var user = userRepository.findForLogin(normalize(account))
                .orElseThrow(() -> invalidCode());
        verify(VerificationCode.Purpose.RESET_PASSWORD, channel, user.getId().toString(), rawCode);
    }

    @Scheduled(cron = "0 20 3 * * *", zone = "Asia/Shanghai")
    @Transactional
    public void deleteExpiredCodes() {
        codeRepository.deleteByExpiresAtBefore(LocalDateTime.now().minusDays(1));
    }

    private void verify(VerificationCode.Purpose purpose, VerificationCode.Channel channel, String subject, String rawCode) {
        var code = codeRepository.findFirstByPurposeAndChannelAndSubjectOrderByCreatedAtDesc(purpose, channel, subject)
                .orElseThrow(() -> invalidCode());
        if (code.getConsumedAt() != null || code.getExpiresAt().isBefore(LocalDateTime.now())
                || code.getAttemptCount() >= properties.verification().maxAttempts()) {
            throw invalidCode();
        }
        if (!MessageDigest.isEqual(
                code.getCodeHash().getBytes(StandardCharsets.US_ASCII),
                hash(purpose, channel, subject, rawCode == null ? "" : rawCode.trim()).getBytes(StandardCharsets.US_ASCII))) {
            code.setAttemptCount(code.getAttemptCount() + 1);
            codeRepository.save(code);
            throw invalidCode();
        }
        code.setConsumedAt(LocalDateTime.now());
        codeRepository.save(code);
    }

    private void deliver(VerificationCode.Channel channel, String destination, String code,
                         VerificationCode.Purpose purpose) {
        try {
            if (channel == VerificationCode.Channel.EMAIL) {
                var mail = new SimpleMailMessage();
                mail.setTo(destination);
                mail.setSubject(purpose == VerificationCode.Purpose.REGISTER
                        ? "河南省教育科研网安全智能体注册验证码" : "河南省教育科研网安全智能体密码重置验证码");
                mail.setText("您的动态验证码是：" + code + "\n验证码在 "
                        + properties.verification().ttlMinutes() + " 分钟内有效，请勿泄露给他人。");
                mailSender.send(mail);
                return;
            }

            var sms = properties.verification().sms();
            if (sms.webhookUrl() == null || sms.webhookUrl().isBlank()) {
                if (properties.verification().exposeCodeInDev()) return;
                throw new IllegalStateException("未配置短信服务网关");
            }
            var payload = new LinkedHashMap<String, Object>();
            payload.put("phone", destination);
            payload.put("code", code);
            payload.put("purpose", purpose.name());
            payload.put("ttlMinutes", properties.verification().ttlMinutes());
            var request = restClientBuilder.build().post().uri(sms.webhookUrl()).body(payload);
            if (sms.authToken() != null && !sms.authToken().isBlank()) {
                request = request.header("Authorization", "Bearer " + sms.authToken());
            }
            request.retrieve().toBodilessEntity();
        } catch (RuntimeException exception) {
            if (properties.verification().exposeCodeInDev()) return;
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VERIFICATION_DELIVERY_FAILED",
                    channel == VerificationCode.Channel.EMAIL ? "邮件发送失败，请稍后重试" : "短信发送失败，请稍后重试");
        }
    }

    private void ensureAvailable(VerificationCode.Channel channel, String target) {
        boolean exists = channel == VerificationCode.Channel.EMAIL
                ? userRepository.existsByEmailIgnoreCase(target) : userRepository.existsByPhone(target);
        if (exists) {
            throw new ApiException(HttpStatus.CONFLICT,
                    channel == VerificationCode.Channel.EMAIL ? "EMAIL_EXISTS" : "PHONE_EXISTS",
                    channel == VerificationCode.Channel.EMAIL ? "邮箱已被使用" : "手机号已被使用");
        }
    }

    private static void validateDestination(VerificationCode.Channel channel, String target) {
        boolean valid = channel == VerificationCode.Channel.EMAIL ? EMAIL.matcher(target).matches() : PHONE.matcher(target).matches();
        if (!valid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DESTINATION",
                    channel == VerificationCode.Channel.EMAIL ? "邮箱格式不正确" : "手机号格式不正确");
        }
    }

    private String hash(VerificationCode.Purpose purpose, VerificationCode.Channel channel, String subject, String code) {
        try {
            String value = purpose + "|" + channel + "|" + subject + "|" + code + "|" + properties.verification().hashSecret();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static VerificationCode.Purpose parsePurpose(String value) {
        try { return VerificationCode.Purpose.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PURPOSE", "验证码用途不正确"); }
    }

    private static VerificationCode.Channel parseChannel(String value) {
        try { return VerificationCode.Channel.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHANNEL", "验证码渠道不正确"); }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String mask(VerificationCode.Channel channel, String value) {
        if (channel == VerificationCode.Channel.PHONE && value.length() == 11) {
            return value.substring(0, 3) + "****" + value.substring(7);
        }
        int at = value.indexOf('@');
        if (at > 0) return value.substring(0, Math.min(2, at)) + "***" + value.substring(at);
        return "***";
    }

    private static ApiException invalidCode() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_CODE", "验证码错误、已过期或已使用");
    }

    public record SendResult(String message, String maskedTarget, long expiresInSeconds, String devCode) {
        static SendResult hidden(long ttl) {
            return new SendResult("如果账号和验证方式匹配，验证码将发送到登记的联系方式", null, ttl, null);
        }
    }
}
