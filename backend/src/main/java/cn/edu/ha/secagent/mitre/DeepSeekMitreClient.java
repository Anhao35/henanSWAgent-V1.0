package cn.edu.ha.secagent.mitre;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class DeepSeekMitreClient {
    private static final int MAX_REPAIR_SOURCE_LENGTH = 24000;
    private static final String ATTACK_SYSTEM_PROMPT = """
            你是严谨的网络威胁分析师，负责将用户提供的自然语言攻击描述映射到 MITRE ATT&CK Enterprise。
            只允许依据用户文本中明确存在的攻击行为进行映射，不得因为出现 CVE、恶意软件名称或宽泛安全词就猜测技术。
            使用当前通行的 ATT&CK 技术/子技术编号（Txxxx 或 Txxxx.xxx）和战术编号（TAxxxx）。
            每一条映射必须包含一段直接摘自用户原文的简短证据；证据不足的观察放入 unmappedObservations。
            最多返回 12 条最相关映射，confidence 为 0 到 1。输出必须是单个 JSON 对象，不要输出 Markdown。
            JSON 结构：
            {"summary":"攻击链概述","attackBehaviors":["按发生顺序描述的行为"],"attackMappings":[{"behaviorOrder":1,"tacticId":"TA0001","tacticName":"Initial Access","techniqueId":"T1566.001","techniqueName":"Spearphishing Attachment","confidence":0.95,"evidence":"用户原文中的短句","reasoning":"为何与该技术匹配"}],"cweMappings":[],"unmappedObservations":["证据不足、不能可靠映射的内容"]}
            """;

    private static final String CWE_SYSTEM_PROMPT = """
            你是严谨的软件安全分析师，负责将用户提供的自然语言漏洞或弱点描述映射到 MITRE CWE。
            CWE 表示弱点类别，不是具体 CVE。只依据用户描述中明确存在的根因或缺陷进行映射；仅有攻击结果而没有弱点根因时不要猜测。
            使用 CWE-数字 格式，并给出通行的英文 CWE 名称。每一条映射必须包含一段直接摘自用户原文的简短证据。
            最多返回 12 条最相关映射，confidence 为 0 到 1。证据不足的观察放入 unmappedObservations。
            输出必须是单个 JSON 对象，不要输出 Markdown。
            JSON 结构：
            {"summary":"漏洞与根因概述","attackBehaviors":[],"attackMappings":[],"cweMappings":[{"cweId":"CWE-79","cweName":"Improper Neutralization of Input During Web Page Generation","confidence":0.95,"evidence":"用户原文中的短句","reasoning":"为何与该弱点匹配"}],"unmappedObservations":["证据不足、不能可靠映射的内容"]}
            """;

    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public ClientResult map(UUID userId, String type, String description) {
        var config = properties.deepSeek();
        if (config == null || config.apiKey() == null || config.apiKey().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEEPSEEK_NOT_CONFIGURED", "MITRE 映射服务尚未配置 DeepSeek API Key");
        }

        return complete(userId, List.of(
                Map.of("role", "system", "content", "ATTACK".equals(type) ? ATTACK_SYSTEM_PROMPT : CWE_SYSTEM_PROMPT),
                Map.of("role", "user", "content", "待分析的原始描述如下：\n\n" + description)
        ));
    }

    public ClientResult repair(UUID userId, String type, String description, String malformedJson) {
        var expected = "ATTACK".equals(type) ? ATTACK_SYSTEM_PROMPT : CWE_SYSTEM_PROMPT;
        var source = malformedJson == null ? "" : malformedJson;
        if (source.length() > MAX_REPAIR_SOURCE_LENGTH) source = source.substring(0, MAX_REPAIR_SOURCE_LENGTH);
        var repairPrompt = """
                下面是一份未能通过程序解析的模型输出。请只修复 JSON 语法、字段名称和字段类型，不要增加原始描述中不存在的事实。
                必须严格遵循系统消息给出的 JSON 结构，只输出一个 JSON 对象，不要输出 Markdown 或解释。

                原始用户描述：
                %s

                待修复输出：
                %s
                """.formatted(description, source);
        return complete(userId, List.of(
                Map.of("role", "system", "content", expected),
                Map.of("role", "user", "content", repairPrompt)
        ));
    }

    private ClientResult complete(UUID userId, List<Map<String, String>> messages) {
        var config = properties.deepSeek();
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("model", config.model());
            payload.put("messages", messages);
            payload.put("response_format", Map.of("type", "json_object"));
            payload.put("temperature", 0);
            payload.put("max_tokens", 6000);
            payload.put("stream", false);
            payload.put("user_id", "hnsec_" + userId);

            var request = HttpRequest.newBuilder(URI.create(endpoint(config.baseUrl())))
                    .timeout(Duration.ofSeconds(Math.max(30, config.timeoutSeconds())))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw providerError(response.statusCode());

            var root = objectMapper.readTree(response.body());
            var content = root.path("choices").path(0).path("message").path("content").asText();
            if (content.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "DEEPSEEK_EMPTY_RESULT", "模型没有返回可用的映射结果");
            var usage = root.path("usage");
            return new ClientResult(cleanJson(content), usage.path("prompt_tokens").isNumber() ? usage.path("prompt_tokens").asInt() : null,
                    usage.path("completion_tokens").isNumber() ? usage.path("completion_tokens").asInt() : null);
        } catch (ApiException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DEEPSEEK_INTERRUPTED", "MITRE 映射请求已中断，请重试");
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DEEPSEEK_UNAVAILABLE", "DeepSeek 暂时不可用，请稍后重试");
        }
    }

    private ApiException providerError(int status) {
        if (status == 401 || status == 403) return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEEPSEEK_AUTH_FAILED", "DeepSeek 鉴权失败，请检查 API Key");
        if (status == 402) return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEEPSEEK_BALANCE_LOW", "DeepSeek 账户余额不足");
        if (status == 429) return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "DEEPSEEK_RATE_LIMIT", "DeepSeek 请求繁忙，请稍后重试");
        return new ApiException(HttpStatus.BAD_GATEWAY, "DEEPSEEK_ERROR", "DeepSeek 服务返回异常（" + status + "）");
    }

    private String endpoint(String baseUrl) {
        var base = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com" : baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/chat/completions")) return base;
        return base.endsWith("/v1") ? base + "/chat/completions" : base + "/v1/chat/completions";
    }

    private String cleanJson(String content) {
        var value = content.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        return value;
    }

    public record ClientResult(String json, Integer promptTokens, Integer completionTokens) {}
}
