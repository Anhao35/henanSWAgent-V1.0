package cn.edu.ha.secagent.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class EpssProvider extends EvidenceHttpSupport {
    public EpssProvider(EvidenceProperties properties, ObjectMapper mapper) { super(properties, mapper); }
    public String source() { return "FIRST EPSS"; }
    public boolean supports(String type) { return "cve".equals(type); }
    public boolean configured() { return true; }

    public EvidenceDtos.Item query(String type, String indicator) {
        var sourceUrl = "https://www.first.org/epss/";
        return safeQuery(sourceUrl, () -> {
            var response = get(properties.endpoints().epss() + "?cve=" + query(indicator), Map.of());
            if (response.status() != 200)
                return EvidenceDtos.Item.failed(source(), "HTTP_" + response.status(), "来源返回异常状态", sourceUrl);
            var rows = response.body().path("data");
            if (!rows.isArray() || rows.isEmpty()) return EvidenceDtos.Item.notFound(source(), "EPSS 未返回该 CVE", sourceUrl);
            var row = rows.get(0);
            double score = number(text(row, "epss"));
            double percentile = number(text(row, "percentile"));
            var attrs = new LinkedHashMap<String, Object>();
            attrs.put("epssProbability", score); attrs.put("percentile", percentile);
            put(attrs, "date", text(row, "date"));
            String verdict = score >= 0.5 ? "HIGH_EXPLOIT_PROBABILITY" : score >= 0.1 ? "ELEVATED_EXPLOIT_PROBABILITY" : "LOWER_EXPLOIT_PROBABILITY";
            return EvidenceDtos.Item.found(source(), verdict,
                    String.format("未来 30 天被利用概率 %.2f%%，百分位 %.2f%%", score * 100, percentile * 100), sourceUrl, attrs);
        });
    }

    private static double number(String value) { try { return Double.parseDouble(value); } catch (Exception ignored) { return 0; } }
    private static void put(Map<String, Object> target, String key, String value) { if (value != null && !value.isBlank()) target.put(key, value); }
}
