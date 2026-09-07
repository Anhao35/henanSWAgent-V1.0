package cn.edu.ha.secagent.auth;

import cn.edu.ha.secagent.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final AuthService authService;
    private final VerificationCodeService verificationCodeService;

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @PostMapping("/register")
    ResponseEntity<?> register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        var user = authService.register(request);
        return ResponseEntity.status(201).body(Map.of(
                "message", "注册成功",
                "requiresApproval", "PENDING".equals(user.status()),
                "user", user
        ));
    }

    @PostMapping("/login")
    Map<String, Object> login(@Valid @RequestBody AuthDtos.LoginRequest body,
                              HttpServletRequest request,
                              HttpServletResponse response) {
        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(body.login(), body.password()));
        request.getSession(true);
        request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        var principal = (AuthenticatedUser) authentication.getPrincipal();
        authService.recordLogin(principal.id());
        return Map.of("message", "登录成功", "user", authService.getUser(principal.id()));
    }

    @PostMapping("/logout")
    Map<String, String> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return Map.of("message", "已退出登录");
    }

    @GetMapping("/me")
    Object me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return authService.getUser(principal.id());
    }

    @PostMapping("/forgot-password")
    Map<String, Object> forgotPassword(@Valid @RequestBody AuthDtos.ForgotPasswordRequest request) {
        var result = authService.createPasswordReset(request.account(), request.channel());
        var response = new LinkedHashMap<String, Object>();
        response.put("message", result.message());
        response.put("expiresInSeconds", result.expiresInSeconds());
        if (result.maskedTarget() != null) response.put("maskedTarget", result.maskedTarget());
        if (result.devCode() != null) response.put("devCode", result.devCode());
        return response;
    }

    @PostMapping("/verification-codes")
    Map<String, Object> sendVerificationCode(@Valid @RequestBody AuthDtos.SendVerificationCodeRequest request) {
        var result = verificationCodeService.send(request);
        var response = new LinkedHashMap<String, Object>();
        response.put("message", result.message());
        response.put("expiresInSeconds", result.expiresInSeconds());
        if (result.maskedTarget() != null) response.put("maskedTarget", result.maskedTarget());
        if (result.devCode() != null) response.put("devCode", result.devCode());
        return response;
    }

    @PostMapping("/reset-password")
    Map<String, String> resetPassword(@Valid @RequestBody AuthDtos.ResetPasswordRequest request) {
        authService.resetPassword(request.account(), request.channel(), request.code(), request.newPassword());
        return Map.of("message", "密码重置成功，请重新登录");
    }

    @PostMapping("/change-password")
    Map<String, String> changePassword(@AuthenticationPrincipal AuthenticatedUser principal,
                                       @Valid @RequestBody AuthDtos.ChangePasswordRequest request) {
        authService.changePassword(principal.id(), request.currentPassword(), request.newPassword());
        return Map.of("message", "密码修改成功");
    }
}
