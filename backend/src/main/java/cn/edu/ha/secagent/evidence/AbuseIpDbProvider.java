package cn.edu.ha.secagent.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AbuseIpDbProvider extends EvidenceHttpSupport {
    public AbuseIpDbProvider(EvidenceProperties properties, ObjectMapper mapper) { super(properties, mapper); }
    public String source() { return "AbuseIPDB"; }
    public boolean supports(String type) { return "ip".equals(type); }
    public boolean configured() { return hasKey(properties.abuseIpDbApiKey()); }

    public EvidenceDtos.Item query(String type, String indicator) {
        var sourceUrl = "https://www.abuseipdb.com/check/" + path(indicator);
        return safeQuery(sourceUrl, () -> {
            var endpoint = properties.endpoints().abuseIpDb() + "?ipAddress=" + query(indicator)
                    + "&maxAgeInDays=" + Math.max(1, properties.abuseIpDbMaxAgeDays()) + "&verbose";
            var response = get(endpoint, Map.of("Key", properties.abuseIpDbApiKey()));
            if (response.status() == 401 || response.status() == 403)
                return EvidenceDtos.Item.failed(source(), "AUTH_FAILED", "API Key 无效或无权访问", sourceUrl);
            if (response.status() != 200)
                return EvidenceDtos.Item.failed(source(), "HTTP_" + response.status(), "来源返回异常状态", sourceUrl);
            var data = response.body().path("data");
            if (!data.isObject()) return EvidenceDtos.Item.notFound(source(), "未返回该 IP 的记录", sourceUrl);
            int score = data.path("abuseConfidenceScore").asInt(0);
            int reports = data.path("totalReports").asInt(0);
            String verdict = score >= 75 ? "HIGH_RISK" : score >= 25 ? "SUSPICIOUS" : reports > 0 ? "OBSERVED" : "NO_REPORTS";
            var attrs = new LinkedHashMap<String, Object>();
            attrs.put("abuseConfidenceScore", score);
            attrs.put("totalReports", reports);
            put(attrs, "countryCode", text(data, "countryCode"));
            put(attrs, "usageType", text(data, "usageType"));
            put(attrs, "isp", text(data, "isp"));
            put(attrs, "domain", text(data, "domain"));
            attrs.put("isTor", data.path("isTor").asBoolean(false));
            put(attrs, "lastReportedAt", text(data, "lastReportedAt"));
            return EvidenceDtos.Item.found(source(), verdict,
                    "滥用置信分 " + score + "/100，近窗口报告 " + reports + " 次", sourceUrl, attrs);
        });
    }

    private static void put(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }
}
