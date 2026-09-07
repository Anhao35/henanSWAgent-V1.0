package cn.edu.ha.secagent.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AbuseChProvider extends EvidenceHttpSupport {
    public AbuseChProvider(EvidenceProperties properties, ObjectMapper mapper) { super(properties, mapper); }
    public String source() { return "abuse.ch ThreatFox"; }
    public boolean supports(String type) { return !"cve".equals(type); }
    public boolean configured() { return hasKey(properties.abuseChAuthKey()); }

    public EvidenceDtos.Item query(String type, String indicator) {
        var sourceUrl = "https://threatfox.abuse.ch/browse.php?search=ioc%3A" + query(indicator);
        return safeQuery(sourceUrl, () -> {
            var response = postJson(properties.endpoints().threatFox(),
                    Map.of("Auth-Key", properties.abuseChAuthKey()),
                    Map.of("query", "get_ioc", "search_term", indicator));
            if (response.status() == 401 || response.status() == 403)
                return EvidenceDtos.Item.failed(source(), "AUTH_FAILED", "Auth-Key 无效或无权访问", sourceUrl);
            if (response.status() != 200)
                return EvidenceDtos.Item.failed(source(), "HTTP_" + response.status(), "来源返回异常状态", sourceUrl);
            var status = text(response.body(), "query_status");
            var rows = response.body().path("data");
            if (!"ok".equalsIgnoreCase(status) || !rows.isArray() || rows.isEmpty())
                return EvidenceDtos.Item.notFound(source(), "ThreatFox 未返回匹配记录；未检出不等于安全", sourceUrl);
            var first = rows.get(0);
            var attrs = new LinkedHashMap<String, Object>();
            attrs.put("recordCount", rows.size());
            put(attrs, "threatType", text(first, "threat_type"));
            put(attrs, "malware", text(first, "malware_printable"));
            put(attrs, "confidenceLevel", text(first, "confidence_level"));
            put(attrs, "firstSeen", text(first, "first_seen"));
            put(attrs, "lastSeen", text(first, "last_seen"));
            return EvidenceDtos.Item.found(source(), "MALICIOUS_OBSERVATION",
                    "ThreatFox 返回 " + rows.size() + " 条威胁记录", sourceUrl, attrs);
        });
    }

    private static void put(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }
}
