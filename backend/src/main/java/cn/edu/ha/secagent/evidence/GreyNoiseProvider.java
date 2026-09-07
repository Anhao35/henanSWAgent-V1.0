package cn.edu.ha.secagent.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GreyNoiseProvider extends EvidenceHttpSupport {
    public GreyNoiseProvider(EvidenceProperties properties, ObjectMapper mapper) { super(properties, mapper); }
    public String source() { return "GreyNoise"; }
    public boolean supports(String type) { return "ip".equals(type); }
    public boolean configured() { return hasKey(properties.greyNoiseApiKey()); }

    public EvidenceDtos.Item query(String type, String indicator) {
        var sourceUrl = "https://viz.greynoise.io/ip/" + path(indicator);
        return safeQuery(sourceUrl, () -> {
            var response = get(properties.endpoints().greyNoise() + "/" + path(indicator),
                    Map.of("key", properties.greyNoiseApiKey()));
            if (response.status() == 404)
                return EvidenceDtos.Item.notFound(source(), "未在 GreyNoise 的扫描器/公共服务数据集中观察到；不代表安全", sourceUrl);
            if (response.status() == 401 || response.status() == 403)
                return EvidenceDtos.Item.failed(source(), "AUTH_FAILED", "API Key 无效或无权访问", sourceUrl);
            if (response.status() == 429)
                return EvidenceDtos.Item.failed(source(), "RATE_LIMITED", "免费额度已用尽", sourceUrl);
            if (response.status() != 200)
                return EvidenceDtos.Item.failed(source(), "HTTP_" + response.status(), "来源返回异常状态", sourceUrl);
            var body = response.body();
            boolean noise = body.path("noise").asBoolean(false);
            boolean riot = body.path("riot").asBoolean(false);
            var classification = text(body, "classification");
            String verdict = riot ? "KNOWN_SERVICE" : "malicious".equalsIgnoreCase(classification) ? "MALICIOUS" : noise ? "INTERNET_SCANNER" : "OBSERVED";
            var attrs = new LinkedHashMap<String, Object>();
            attrs.put("noise", noise); attrs.put("riot", riot);
            put(attrs, "classification", classification); put(attrs, "name", text(body, "name"));
            put(attrs, "lastSeen", text(body, "last_seen"));
            return EvidenceDtos.Item.found(source(), verdict,
                    riot ? "命中已知公共服务（RIOT）" : noise ? "观察到互联网扫描活动" : "GreyNoise 返回记录", sourceUrl, attrs);
        });
    }

    private static void put(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }
}
