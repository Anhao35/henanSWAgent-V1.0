package cn.edu.ha.secagent.research;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class ResearchDtos {
    private ResearchDtos() {}

    public record SearchRequest(
            @NotBlank @Size(min = 2, max = 500) String query,
            String type,
            List<String> sources,
            @Min(1900) Integer yearFrom,
            @Min(1900) Integer yearTo,
            boolean openAccessOnly,
            @Min(1) @Max(50) Integer limit
    ) {
        public String normalizedType() {
            var value = type == null ? "ALL" : type.trim().toUpperCase();
            return List.of("ALL", "PAPER", "BOOK").contains(value) ? value : "ALL";
        }

        public int normalizedLimit() {
            return limit == null ? 20 : Math.max(1, Math.min(limit, 50));
        }
    }

    public record SearchItem(
            String source,
            String sourceId,
            String itemType,
            String title,
            List<String> authors,
            Integer publicationYear,
            String venue,
            String abstractText,
            String doi,
            String sourceUrl,
            String openAccessUrl,
            Integer citationCount,
            double score
    ) {}

    public record SearchResponse(
            UUID searchId,
            String query,
            int total,
            long durationMs,
            boolean cached,
            List<String> warnings,
            List<SearchItem> items
    ) {}

    public record SourceView(String code, String name, boolean available, boolean requiresFreeKey) {}

    public record HistoryView(
            UUID id,
            String query,
            String sources,
            int resultCount,
            long durationMs,
            String status,
            LocalDateTime createdAt
    ) {}

    public record SaveRequest(
            @NotBlank String source,
            @NotBlank String sourceId,
            @NotBlank String itemType,
            @NotBlank @Size(max = 1000) String title,
            List<String> authors,
            Integer publicationYear,
            String venue,
            String abstractText,
            String doi,
            String sourceUrl,
            String openAccessUrl,
            Integer citationCount
    ) {}

    public record SavedView(
            UUID id,
            String source,
            String sourceId,
            String itemType,
            String title,
            List<String> authors,
            Integer publicationYear,
            String venue,
            String abstractText,
            String doi,
            String sourceUrl,
            String openAccessUrl,
            Integer citationCount,
            LocalDateTime createdAt
    ) {}
}
