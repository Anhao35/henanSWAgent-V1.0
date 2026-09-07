package cn.edu.ha.secagent.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class CirclHashlookupProvider extends EvidenceHttpSupport {
    public CirclHashlookupProvider(EvidenceProperties properties, ObjectMapper mapper) { super(properties, mapper); }
    public String source() { return "CIRCL Hashlookup"; }
    public boolean supports(String type) { return "hash".equals(type); }
    public boolean configured() { return true; }

    public EvidenceDtos.Item query(String type, String indicator) {
        var sourceUrl = "https://hashlookup.circl.lu/";
        return safeQuery(sourceUrl, () -> {
            var response = get(properties.endpoints().circlHashlookup() + "/" + path(indicator), Map.of());
            if (response.status() == 404)
                return EvidenceDtos.Item.notFound(source(), "未命中已知良性软件哈希库；这不代表样本恶意", sourceUrl);
            if (response.status() != 200)
                return EvidenceDtos.Item.failed(source(), "HTTP_" + response.status(), "来源返回异常状态", sourceUrl);
            var body = response.body();
            var attrs = new LinkedHashMap<String, Object>();
            put(attrs, "fileName", first(text(body, "FileName"), text(body, "file_name")));
            put(attrs, "fileSize", first(text(body, "FileSize"), text(body, "file_size")));
            put(attrs, "source", first(text(body, "source"), text(body, "db")));
            return EvidenceDtos.Item.found(source(), "KNOWN_BENIGN",
                    "命中 CIRCL 已知软件哈希库，可作为良性来源证据之一", sourceUrl, attrs);
        });
    }

    private static String first(String a, String b) { return a == null || a.isBlank() ? b : a; }
    private static void put(Map<String, Object> target, String key, String value) { if (value != null && !value.isBlank()) target.put(key, value); }
}
