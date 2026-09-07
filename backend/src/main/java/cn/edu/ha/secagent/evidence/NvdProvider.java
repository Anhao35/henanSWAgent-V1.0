package cn.edu.ha.secagent.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class NvdProvider extends EvidenceHttpSupport {
    public NvdProvider(EvidenceProperties properties, ObjectMapper mapper) { super(properties, mapper); }
    public String source() { return "NIST NVD"; }
    public boolean supports(String type) { return "cve".equals(type); }
    public boolean configured() { return hasKey(properties.nvdApiKey()); }

    public EvidenceDtos.Item query(String type, String indicator) {
        var sourceUrl = "https://nvd.nist.gov/vuln/detail/" + path(indicator);
        return safeQuery(sourceUrl, () -> {
            var response = get(properties.endpoints().nvd() + "?cveId=" + query(indicator),
                    Map.of("apiKey", properties.nvdApiKey()));
            if (response.status() == 401 || response.status() == 403)
                return EvidenceDtos.Item.failed(source(), "AUTH_FAILED", "NVD API Key 无效或尚未激活", sourceUrl);
            if (response.status() == 429)
                return EvidenceDtos.Item.failed(source(), "RATE_LIMITED", "NVD 请求频率受限", sourceUrl);
            if (response.status() != 200)
                return EvidenceDtos.Item.failed(source(), "HTTP_" + response.status(), "来源返回异常状态", sourceUrl);
            var vulnerabilities = response.body().path("vulnerabilities");
            if (!vulnerabilities.isArray() || vulnerabilities.isEmpty())
                return EvidenceDtos.Item.notFound(source(), "NVD 未返回该 CVE", sourceUrl);
            var cve = vulnerabilities.get(0).path("cve");
            var attrs = new LinkedHashMap<String, Object>();
            put(attrs, "published", text(cve, "published")); put(attrs, "lastModified", text(cve, "lastModified"));
            var description = englishDescription(cve.path("descriptions"));
            put(attrs, "description", limit(description, 700));
            var cvss = cvss(cve.path("metrics"));
            if (cvss != null) {
                attrs.put("cvssVersion", cvss.path("version").asText());
                attrs.put("baseScore", cvss.path("baseScore").asDouble());
                put(attrs, "baseSeverity", text(cvss, "baseSeverity"));
                put(attrs, "vectorString", text(cvss, "vectorString"));
            }
            var weaknesses = cve.path("weaknesses");
            if (weaknesses.isArray() && !weaknesses.isEmpty()) {
                var descriptions = weaknesses.get(0).path("description");
                if (descriptions.isArray() && !descriptions.isEmpty()) put(attrs, "weakness", text(descriptions.get(0), "value"));
            }
            String severity = cvss == null ? "UNSCORED" : text(cvss, "baseSeverity");
            return EvidenceDtos.Item.found(source(), severity.isBlank() ? "UNSCORED" : severity,
                    cvss == null ? "NVD 已收录，暂未取得 CVSS 评分" : "NVD 已收录，CVSS " + cvss.path("baseScore").asText(),
                    sourceUrl, attrs);
        });
    }

    private static JsonNode cvss(JsonNode metrics) {
        for (var key : new String[]{"cvssMetricV40", "cvssMetricV31", "cvssMetricV30", "cvssMetricV2"}) {
            var values = metrics.path(key);
            if (values.isArray() && !values.isEmpty()) return values.get(0).path("cvssData");
        }
        return null;
    }

    private static String englishDescription(JsonNode descriptions) {
        if (!descriptions.isArray()) return "";
        for (var item : descriptions) if ("en".equalsIgnoreCase(text(item, "lang"))) return text(item, "value");
        return descriptions.isEmpty() ? "" : text(descriptions.get(0), "value");
    }

    private static void put(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }
}
