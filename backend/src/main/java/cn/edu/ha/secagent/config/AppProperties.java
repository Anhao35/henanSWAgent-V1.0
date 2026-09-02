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
        Minio minio,
        Dify dify
) {
    public record BootstrapAdmin(boolean enabled, String username, String password, String email) {}
    public record Registration(boolean requireApproval) {}
    public record PasswordReset(String publicBaseUrl, int ttlMinutes, boolean exposeTokenInDev) {}
    public record Minio(String endpoint, String accessKey, String secretKey, String bucket) {}
    public record Dify(String baseUrl, String apiKey, int timeoutSeconds, Map<String, String> workflowKeys) {
        public Dify {
            workflowKeys = workflowKeys == null ? new LinkedHashMap<>() : workflowKeys;
        }
    }
}

