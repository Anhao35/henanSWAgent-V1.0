package cn.edu.ha.secagent.evidence;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class EvidenceDtos {
    private EvidenceDtos() {}

    public record Item(
            String source,
            boolean querySuccess,
            boolean found,
            String verdict,
            String summary,
            String sourceUrl,
            Map<String, Object> attributes,
            String errorCode
    ) {
        public static Item found(String source, String verdict, String summary, String sourceUrl,
                                 Map<String, Object> attributes) {
            return new Item(source, true, true, verdict, summary, sourceUrl,
                    attributes == null ? Map.of() : attributes, null);
        }

        public static Item notFound(String source, String summary, String sourceUrl) {
            return new Item(source, true, false, "NOT_FOUND", summary, sourceUrl, Map.of(), null);
        }

        public static Item failed(String source, String code, String summary, String sourceUrl) {
            return new Item(source, false, false, "UNKNOWN", summary, sourceUrl, Map.of(), code);
        }
    }

    public record Bundle(
            String schemaVersion,
            String indicatorType,
            String indicator,
            Instant queriedAt,
            List<Item> sources
    ) {
        public long successfulSources() { return sources.stream().filter(Item::querySuccess).count(); }
        public long failedSources() { return sources.stream().filter(item -> !item.querySuccess()).count(); }
        public long matchedSources() { return sources.stream().filter(Item::found).count(); }
        public boolean partial() { return failedSources() > 0; }
    }
}
