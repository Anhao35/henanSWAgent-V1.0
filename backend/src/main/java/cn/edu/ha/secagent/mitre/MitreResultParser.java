package cn.edu.ha.secagent.mitre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tolerant boundary parser for model-generated JSON. Domain validation remains in
 * {@link MitreMappingService}; this class only absorbs harmless representation drift.
 */
@Component
@RequiredArgsConstructor
public class MitreResultParser {
    private final ObjectMapper objectMapper;

    public MitreDtos.LlmResult parse(String json) throws Exception {
        var root = unwrap(objectMapper.readTree(json));
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("顶层结果不是 JSON 对象");
        }

        var summary = text(root, "summary", "overview", "conclusion");
        var behaviors = stringList(first(root, "attackBehaviors", "attack_behaviors", "behaviors", "behaviorChain"));
        var observations = stringList(first(root, "unmappedObservations", "unmapped_observations", "unmapped", "observations"));
        var attacks = attackMappings(first(root, "attackMappings", "attack_mappings", "mappings", "techniques"));
        var cwes = cweMappings(first(root, "cweMappings", "cwe_mappings", "mappings", "weaknesses"));

        if (summary.isBlank() && behaviors.isEmpty() && observations.isEmpty() && attacks.isEmpty() && cwes.isEmpty()) {
            throw new IllegalArgumentException("结果中没有可识别的映射字段");
        }
        return new MitreDtos.LlmResult(summary, behaviors, attacks, cwes, observations);
    }

    private JsonNode unwrap(JsonNode root) {
        if (root == null || !root.isObject()) return root;
        for (var key : List.of("result", "data", "output")) {
            var child = root.get(key);
            if (child != null && child.isObject()) return child;
        }
        return root;
    }

    private List<MitreDtos.LlmAttackMapping> attackMappings(JsonNode node) {
        var result = new ArrayList<MitreDtos.LlmAttackMapping>();
        for (var item : elements(node)) {
            if (!item.isObject()) continue;
            var techniqueId = text(item, "techniqueId", "technique_id", "techniqueID", "attackId", "id");
            if (techniqueId.isBlank() || !techniqueId.toUpperCase(Locale.ROOT).startsWith("T")) continue;
            result.add(new MitreDtos.LlmAttackMapping(
                    integer(item, "behaviorOrder", "behavior_order", "order"),
                    text(item, "tacticId", "tactic_id", "tacticID"),
                    text(item, "tacticName", "tactic_name", "tactic"),
                    techniqueId,
                    text(item, "techniqueName", "technique_name", "name"),
                    confidence(item),
                    text(item, "evidence", "sourceEvidence", "source_evidence", "quote"),
                    text(item, "reasoning", "reason", "explanation")
            ));
        }
        return result;
    }

    private List<MitreDtos.LlmCweMapping> cweMappings(JsonNode node) {
        var result = new ArrayList<MitreDtos.LlmCweMapping>();
        for (var item : elements(node)) {
            if (!item.isObject()) continue;
            var id = text(item, "cweId", "cwe_id", "cweID", "id");
            if (id.isBlank() || !id.toUpperCase(Locale.ROOT).startsWith("CWE")) continue;
            result.add(new MitreDtos.LlmCweMapping(
                    id,
                    text(item, "cweName", "cwe_name", "name"),
                    confidence(item),
                    text(item, "evidence", "sourceEvidence", "source_evidence", "quote"),
                    text(item, "reasoning", "reason", "explanation")
            ));
        }
        return result;
    }

    private Iterable<JsonNode> elements(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return List.of();
        if (node.isArray()) return node;
        if (node.isObject()) return List.of(node);
        return List.of();
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return List.of();
        var values = new ArrayList<String>();
        if (node.isArray()) {
            for (var item : node) {
                var value = scalarText(item);
                if (!value.isBlank()) values.add(value);
            }
        } else {
            var value = scalarText(node);
            if (!value.isBlank()) values.add(value);
        }
        return values;
    }

    private Double confidence(JsonNode node) {
        var value = first(node, "confidence", "confidenceScore", "confidence_score", "score");
        if (value == null || value.isNull()) return 0d;
        try {
            var raw = value.isNumber() ? value.asDouble() : Double.parseDouble(value.asText().trim().replace("%", ""));
            return raw > 1 && raw <= 100 ? raw / 100d : raw;
        } catch (NumberFormatException ignored) {
            return 0d;
        }
    }

    private Integer integer(JsonNode node, String... keys) {
        var value = first(node, keys);
        if (value == null || value.isNull()) return null;
        if (value.canConvertToInt()) return value.asInt();
        try {
            return Integer.valueOf(value.asText().trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String text(JsonNode node, String... keys) {
        return scalarText(first(node, keys));
    }

    private String scalarText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return "";
        return node.isValueNode() ? node.asText().trim() : "";
    }

    private JsonNode first(JsonNode node, String... keys) {
        if (node == null || !node.isObject()) return null;
        for (var key : keys) {
            var value = node.get(key);
            if (value != null && !value.isNull()) return value;
        }
        return null;
    }
}
