package cn.edu.ha.secagent.common;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<?> handleAccessDenied() {
        return ResponseEntity.status(403).body(Map.of("code","FORBIDDEN","message","没有权限执行此操作"));
    }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> handleApi(ApiException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(Map.of("code", exception.getCode(), "message", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", "提交内容不符合要求",
                "fields", fields
        ));
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<?> handleBadCredentials() {
        return ResponseEntity.status(401).body(Map.of("code", "BAD_CREDENTIALS", "message", "账号或密码错误"));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<?> handleAuthentication(AuthenticationException exception) {
        return ResponseEntity.status(401).body(Map.of("code", "AUTHENTICATION_FAILED", "message", "账号不可用或用户名、密码错误"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> handleUnexpected(Exception exception) {
        return ResponseEntity.internalServerError().body(Map.of("code", "INTERNAL_ERROR", "message", "服务器处理失败"));
    }
}
