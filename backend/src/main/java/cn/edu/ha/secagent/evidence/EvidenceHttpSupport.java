package cn.edu.ha.secagent.evidence;

import cn.edu.ha.secagent.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

abstract class EvidenceHttpSupport implements EvidenceProvider {
    protected final EvidenceProperties properties;
    protected final ObjectMapper mapper;
    private final HttpClient client;

    protected EvidenceHttpSupport(EvidenceProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(3, properties.timeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    protected JsonResponse get(String url, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(3, properties.timeoutSeconds())))
                .header("Accept", "application/json")
                .GET();
        headers.forEach(builder::header);
        return execute(builder.build());
    }

    protected JsonResponse postJson(String url, Map<String, String> headers, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(3, properties.timeoutSeconds())))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        headers.forEach(builder::header);
        return execute(builder.build());
    }

    private JsonResponse execute(HttpRequest request) throws Exception {
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body;
        try { body = response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body()); }
        catch (Exception ignored) { body = mapper.createObjectNode(); }
        return new JsonResponse(response.statusCode(), body);
    }

    protected EvidenceDtos.Item safeQuery(String sourceUrl, ThrowingSupplier<EvidenceDtos.Item> supplier) {
        try {
            return supplier.get();
        } catch (Exception exception) {
            return EvidenceDtos.Item.failed(source(), "PROVIDER_UNAVAILABLE",
                    "来源请求失败或超时；这不代表目标安全或未检出", sourceUrl);
        }
    }

    protected static String query(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    protected static String path(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    protected static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    protected static String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    protected static boolean hasKey(String key) { return key != null && !key.isBlank(); }

    protected record JsonResponse(int status, JsonNode body) {}
    @FunctionalInterface protected interface ThrowingSupplier<T> { T get() throws Exception; }
}
