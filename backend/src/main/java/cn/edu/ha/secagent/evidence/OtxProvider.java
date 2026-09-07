package cn.edu.ha.secagent.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OtxProvider extends EvidenceHttpSupport {
    public OtxProvider(EvidenceProperties properties, ObjectMapper mapper) { super(properties, mapper); }
    public String source() { return "LevelBlue OTX"; }
    public boolean supports(String type) { return true; }
    public boolean configured() { return hasKey(properties.otxApiKey()); }

    public EvidenceDtos.Item query(String type, String indicator) {
        String otxType = switch (type) {
            case "ip" -> indicator.contains(":") ? "IPv6" : "IPv4";
            case "domain" -> "domain";
            case "url" -> "url";
            case "cve" -> "CVE";
            case "hash" -> "file";
            default -> throw new IllegalArgumentException("unsupported indicator type");
        };
        var sourceUrl = "https://otx.alienvault.com/indicator/" + otxType + "/" + path(indicator);
        return safeQuery(sourceUrl, () -> {
            var response = get(properties.endpoints().otx() + "/" + otxType + "/" + path(indicator) + "/general",
                    Map.of("X-OTX-API-KEY", properties.otxApiKey()));
            if (response.status() == 401 || response.status() == 403)
                return EvidenceDtos.Item.failed(source(), "AUTH_FAILED", "OTX API Key 无效或无权访问", sourceUrl);
            if (response.status() == 404)
                return EvidenceDtos.Item.notFound(source(), "OTX 未返回该指标", sourceUrl);
            if (response.status() == 429)
                return EvidenceDtos.Item.failed(source(), "RATE_LIMITED", "OTX 请求频率受限", sourceUrl);
            if (response.status() != 200)
                return EvidenceDtos.Item.failed(source(), "HTTP_" + response.status(), "来源返回异常状态", sourceUrl);
            var body = response.body();
            int pulseCount = body.path("pulse_info").path("count").asInt(0);
            int reputation = body.path("reputation").asInt(0);
            var attrs = new LinkedHashMap<String, Object>();
            attrs.put("pulseCount", pulseCount); attrs.put("reputation", reputation);
            var validation = body.path("validation");
            if (validation.isArray() && !validation.isEmpty()) attrs.put("validationCount", validation.size());
            String verdict = pulseCount > 0 || reputation < 0 ? "COMMUNITY_OBSERVED" : "NO_PULSE";
            return EvidenceDtos.Item.found(source(), verdict,
                    pulseCount > 0 ? "关联 OTX Pulse " + pulseCount + " 个" : "OTX 返回指标记录，但没有关联 Pulse", sourceUrl, attrs);
        });
    }
}
