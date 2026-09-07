package cn.edu.ha.secagent.mitre;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class MitreKnowledgeCatalog {
    private final Map<String, String> attack;
    private final Map<String, String> cwe;

    public MitreKnowledgeCatalog() {
        attack = load("mitre/attack-catalog.tsv");
        cwe = load("mitre/cwe-catalog.tsv");
    }

    public boolean containsTechnique(String id) {
        return id != null && id.startsWith("T") && !id.startsWith("TA") && attack.containsKey(id);
    }

    public boolean containsTactic(String id) {
        return id != null && id.startsWith("TA") && attack.containsKey(id);
    }

    public boolean containsCwe(String id) {
        return id != null && cwe.containsKey(id);
    }

    public Optional<String> attackName(String id) {
        return Optional.ofNullable(attack.get(id));
    }

    public Optional<String> cweName(String id) {
        return Optional.ofNullable(cwe.get(id));
    }

    private Map<String, String> load(String path) {
        var values = new LinkedHashMap<String, String>();
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                var columns = line.split("\\t", 2);
                if (columns.length == 2 && !columns[0].isBlank()) values.put(columns[0].trim(), columns[1].trim());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载 MITRE 本地知识目录：" + path, exception);
        }
        if (values.isEmpty()) throw new IllegalStateException("MITRE 本地知识目录为空：" + path);
        return Collections.unmodifiableMap(values);
    }
}
