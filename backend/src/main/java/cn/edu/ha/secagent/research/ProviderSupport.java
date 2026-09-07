package cn.edu.ha.secagent.research;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

final class ProviderSupport {
    private ProviderSupport() {}

    static String text(JsonNode node, String field) {
        var value = node == null ? null : node.path(field);
        return value == null || value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }

    static Integer integer(JsonNode node, String field) {
        var value = node == null ? null : node.path(field);
        return value == null || !value.isNumber() ? null : value.asInt();
    }

    static List<String> textArray(JsonNode node) {
        var values = new ArrayList<String>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                var value = item.asText("").trim();
                if (!value.isBlank()) values.add(value);
            });
        }
        return values;
    }

    static String firstText(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) return null;
        return node.get(0).asText(null);
    }

    static String cleanAbstract(String value) {
        if (value == null) return null;
        var cleaned = value.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return cleaned.length() > 1800 ? cleaned.substring(0, 1800) + "…" : cleaned;
    }

    static double positionScore(int index, int total) {
        return Math.max(0.05, 1.0 - ((double) index / Math.max(1, total)) * 0.55);
    }
}
