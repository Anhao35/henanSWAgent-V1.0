package cn.edu.ha.secagent.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.knowledge")
public record KnowledgeProperties(
        String baseUrl,
        String apiKey,
        String datasetId,
        int semanticTopK,
        int keywordTopK,
        int maxExcerpts,
        int maxContextChars
) {
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank() && datasetId != null && !datasetId.isBlank();
    }

    public String normalizedBaseUrl() {
        var value = baseUrl == null || baseUrl.isBlank() ? "http://127.0.0.1:8081/v1" : baseUrl;
        return value.replaceAll("/$", "");
    }
}
