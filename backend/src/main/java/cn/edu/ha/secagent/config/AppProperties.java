package cn.edu.ha.secagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String frontendOrigin,
        BootstrapAdmin bootstrapAdmin,
        Registration registration,
        PasswordReset passwordReset,
        Verification verification,
        Minio minio,
        Dify dify,
        Research research,
        DeepSeek deepSeek
) {
    public record BootstrapAdmin(boolean enabled, String username, String password, String email) {}
    public record Registration(boolean requireApproval) {}
    public record PasswordReset(String publicBaseUrl, int ttlMinutes, boolean exposeTokenInDev) {}
    public record Verification(int ttlMinutes, int resendSeconds, int maxAttempts, boolean exposeCodeInDev,
                               String hashSecret, Sms sms) {
        public record Sms(String webhookUrl, String authToken) {}
    }
    public record Minio(String endpoint, String accessKey, String secretKey, String bucket) {}
    public record Dify(String baseUrl, String apiKey, int timeoutSeconds, Map<String, String> workflowKeys) {
        public Dify {
            workflowKeys = workflowKeys == null ? new LinkedHashMap<>() : workflowKeys;
        }
    }
    public record Research(
            int timeoutSeconds,
            int cacheMinutes,
            String crossrefMailto,
            String openAlexApiKey,
            String semanticScholarApiKey,
            boolean openLibraryEnabled
    ) {}
    public record DeepSeek(
            String baseUrl,
            String apiKey,
            String model,
            int timeoutSeconds,
            int requestsPerMinute,
            int cacheHours
    ) {}
}
