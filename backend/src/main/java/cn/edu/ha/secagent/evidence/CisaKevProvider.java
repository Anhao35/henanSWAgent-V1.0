package cn.edu.ha.secagent.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class CisaKevProvider extends EvidenceHttpSupport {
    private volatile JsonNode catalog;
    private volatile Instant fetchedAt = Instant.EPOCH;

    public CisaKevProvider(EvidenceProperties properties, ObjectMapper mapper) { super(properties, mapper); }
    public String source() { return "CISA KEV"; }
    public boolean supports(String type) { return "cve".equals(type); }
    public boolean configured() { return true; }

    public EvidenceDtos.Item query(String type, String indicator) {
        var sourceUrl = "https://www.cisa.gov/known-exploited-vulnerabilities-catalog";
        return safeQuery(sourceUrl, () -> {
            var data = loadCatalog(sourceUrl);
            for (var item : data.path("vulnerabilities")) {
                if (!indicator.equalsIgnoreCase(text(item, "cveID"))) continue;
                var attrs = new LinkedHashMap<String, Object>();
                put(attrs, "vendorProject", text(item, "vendorProject")); put(attrs, "product", text(item, "product"));
                put(attrs, "vulnerabilityName", text(item, "vulnerabilityName")); put(attrs, "dateAdded", text(item, "dateAdded"));
                put(attrs, "dueDate", text(item, "dueDate")); put(attrs, "requiredAction", limit(text(item, "requiredAction"), 500));
                put(attrs, "knownRansomwareCampaignUse", text(item, "knownRansomwareCampaignUse"));
                return EvidenceDtos.Item.found(source(), "KNOWN_EXPLOITED",
                        "已列入 CISA 已知被利用漏洞目录（KEV）", sourceUrl, attrs);
            }
            return EvidenceDtos.Item.notFound(source(), "当前 KEV 目录未列出该 CVE；不代表不存在利用活动", sourceUrl);
        });
    }

    private JsonNode loadCatalog(String sourceUrl) throws Exception {
        if (catalog != null && Duration.between(fetchedAt, Instant.now()).toHours() < 1) return catalog;
        synchronized (this) {
            if (catalog != null && Duration.between(fetchedAt, Instant.now()).toHours() < 1) return catalog;
            var response = get(properties.endpoints().cisaKev(), Map.of());
            if (response.status() != 200) throw new IllegalStateException("CISA KEV HTTP " + response.status());
            catalog = response.body(); fetchedAt = Instant.now(); return catalog;
        }
    }

    private static void put(Map<String, Object> target, String key, String value) { if (value != null && !value.isBlank()) target.put(key, value); }
}
