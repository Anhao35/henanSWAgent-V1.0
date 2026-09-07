package cn.edu.ha.secagent.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}

    public record LoginRequest(@NotBlank String login, @NotBlank String password) {}

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 32) @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "只能包含字母、数字和下划线") String username,
            @NotBlank @Size(max = 64) String displayName,
            @NotBlank @Size(min = 10, max = 72) String password,
            @Email @Size(max = 128) String email,
            @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone,
            @Size(max = 64) String organizationCode,
            @NotBlank @Pattern(regexp = "(?i)EMAIL|PHONE", message = "请选择邮箱或手机号验证") String verificationChannel,
            @NotBlank @Pattern(regexp = "^\\d{4,6}$", message = "验证码格式不正确") String verificationCode
    ) {}

    public record SendVerificationCodeRequest(
            @NotBlank @Pattern(regexp = "(?i)REGISTER|RESET_PASSWORD", message = "验证码用途不正确") String purpose,
            @NotBlank @Pattern(regexp = "(?i)EMAIL|PHONE", message = "请选择邮箱或手机号验证") String channel,
            @NotBlank @Size(max = 128) String target
    ) {}
    public record ForgotPasswordRequest(
            @NotBlank String account,
            @NotBlank @Pattern(regexp = "(?i)EMAIL|PHONE", message = "请选择邮箱或手机号验证") String channel
    ) {}
    public record ResetPasswordRequest(
            @NotBlank String account,
            @NotBlank @Pattern(regexp = "(?i)EMAIL|PHONE", message = "请选择邮箱或手机号验证") String channel,
            @NotBlank @Pattern(regexp = "^\\d{4,6}$", message = "验证码格式不正确") String code,
            @NotBlank @Size(min = 10, max = 72) String newPassword
    ) {}
    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min = 10, max = 72) String newPassword) {}
}
