package cn.edu.ha.secagent.research;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.config.AppProperties;
import cn.edu.ha.secagent.domain.ResearchSavedItem;
import cn.edu.ha.secagent.domain.ResearchSearch;
import cn.edu.ha.secagent.repository.ResearchSavedItemRepository;
import cn.edu.ha.secagent.repository.ResearchSearchRepository;
import cn.edu.ha.secagent.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class ResearchService {
    private final List<ResearchProvider> providers;
    private final ResearchSearchRepository searchRepository;
    private final ResearchSavedItemRepository savedItemRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;

    public List<ResearchDtos.SourceView> sources() {
        var names = java.util.Map.of(
                "ARXIV", "arXiv", "CROSSREF", "Crossref", "OPEN_LIBRARY", "Open Library",
                "OPENALEX", "OpenAlex", "SEMANTIC_SCHOLAR", "Semantic Scholar");
        return providers.stream().map(provider -> new ResearchDtos.SourceView(provider.source(),
                        names.getOrDefault(provider.source(), provider.source()), provider.available(),
                        Set.of("OPENALEX", "SEMANTIC_SCHOLAR").contains(provider.source())))
                .sorted(Comparator.comparing(ResearchDtos.SourceView::code)).toList();
    }

    @Transactional
    public ResearchDtos.SearchResponse search(UUID userId, ResearchDtos.SearchRequest request) {
        validateYears(request);
        var started = System.currentTimeMillis();
        var selected = selectProviders(request);
        if (selected.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "NO_RESEARCH_SOURCE", "没有可用的数据源");

        var cacheKey = cacheKey(request, selected);
        var cached = readCache(cacheKey);
        List<ResearchDtos.SearchItem> items;
        List<String> warnings;
        boolean cacheHit = cached != null;
        if (cacheHit) {
            items = cached.items();
            warnings = cached.warnings();
        } else {
            var futures = selected.stream().map(provider -> CompletableFuture.supplyAsync(() -> query(provider, request))).toList();
            var outcomes = futures.stream().map(CompletableFuture::join).toList();
            warnings = outcomes.stream().filter(outcome -> outcome.warning() != null).map(ProviderOutcome::warning).toList();
            items = rankAndDeduplicate(outcomes.stream().flatMap(outcome -> outcome.items().stream()).toList(), request);
            if (items.size() > request.normalizedLimit()) items = new ArrayList<>(items.subList(0, request.normalizedLimit()));
            if (!items.isEmpty()) writeCache(cacheKey, new CachedPayload(items, warnings));
        }

        var duration = System.currentTimeMillis() - started;
        var history = new ResearchSearch();
        history.setUser(userRepository.getReferenceById(userId));
        history.setQueryText(request.query().trim());
        history.setFiltersJson(toJson(request));
        history.setSources(String.join(",", selected.stream().map(ResearchProvider::source).toList()));
        history.setResultCount(items.size());
        history.setDurationMs(duration);
        history.setStatus(warnings.isEmpty() ? "COMPLETED" : (items.isEmpty() ? "FAILED" : "PARTIAL"));
        searchRepository.save(history);
        return new ResearchDtos.SearchResponse(history.getId(), request.query().trim(), items.size(), duration,
                cacheHit, warnings, items);
    }

    @Transactional(readOnly = true)
    public List<ResearchDtos.HistoryView> history(UUID userId, int limit) {
        return searchRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, Math.max(1, Math.min(limit, 100))))
                .stream().map(item -> new ResearchDtos.HistoryView(item.getId(), item.getQueryText(), item.getSources(),
                        item.getResultCount(), item.getDurationMs(), item.getStatus(), item.getCreatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public List<ResearchDtos.SavedView> saved(UUID userId) {
        return savedItemRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::view).toList();
    }

    @Transactional
    public ResearchDtos.SavedView save(UUID userId, ResearchDtos.SaveRequest request) {
        var existing = savedItemRepository.findByUserIdAndSourceAndSourceId(userId, request.source(), request.sourceId());
        if (existing.isPresent()) return view(existing.get());
        var item = new ResearchSavedItem();
        item.setUser(userRepository.getReferenceById(userId));
        item.setSource(trim(request.source(), 32));
        item.setSourceId(trim(request.sourceId(), 512));
        item.setItemType(trim(request.itemType(), 32));
        item.setTitle(trim(request.title(), 1000));
        item.setAuthors(toJson(request.authors() == null ? List.of() : request.authors()));
        item.setPublicationYear(request.publicationYear());
        item.setVenue(trim(request.venue(), 500));
        item.setAbstractText(request.abstractText());
        item.setDoi(trim(request.doi(), 255));
        item.setSourceUrl(trim(request.sourceUrl(), 1500));
        item.setOpenAccessUrl(trim(request.openAccessUrl(), 1500));
        item.setCitationCount(request.citationCount());
        return view(savedItemRepository.save(item));
    }

    @Transactional
    public void deleteSaved(UUID userId, UUID id) {
        var item = savedItemRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SAVED_ITEM_NOT_FOUND", "收藏记录不存在"));
        savedItemRepository.delete(item);
    }

    private ProviderOutcome query(ResearchProvider provider, ResearchDtos.SearchRequest request) {
        try {
            return new ProviderOutcome(provider.search(request), null);
        } catch (Exception exception) {
            return new ProviderOutcome(List.of(), provider.source() + " 暂时不可用，已返回其他来源结果");
        }
    }

    private List<ResearchProvider> selectProviders(ResearchDtos.SearchRequest request) {
        Set<String> requested = request.sources() == null ? Set.of() : request.sources().stream()
                .map(value -> value.trim().toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return providers.stream().filter(ResearchProvider::available)
                .filter(provider -> provider.supports(request.normalizedType()))
                .filter(provider -> requested.isEmpty() || requested.contains("ALL") || requested.contains(provider.source()))
                .toList();
    }

    private List<ResearchDtos.SearchItem> rankAndDeduplicate(List<ResearchDtos.SearchItem> raw, ResearchDtos.SearchRequest request) {
        var unique = new LinkedHashMap<String, ResearchDtos.SearchItem>();
        for (var item : raw) {
            if (item.title() == null || item.title().isBlank() || item.sourceId() == null || item.sourceId().isBlank()) continue;
            var key = item.doi() != null && !item.doi().isBlank() ? "doi:" + item.doi().toLowerCase(Locale.ROOT)
                    : "title:" + item.title().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
            unique.merge(key, item, this::merge);
        }
        var currentYear = Year.now().getValue();
        return unique.values().stream().map(item -> {
                    double score = item.score();
                    if (item.citationCount() != null) score += Math.min(0.18, Math.log10(item.citationCount() + 1) * 0.05);
                    if (item.publicationYear() != null && currentYear - item.publicationYear() <= 3) score += 0.05;
                    var lowerTitle = item.title().toLowerCase(Locale.ROOT);
                    var terms = request.query().toLowerCase(Locale.ROOT).split("\\s+");
                    for (var term : terms) if (term.length() > 1 && lowerTitle.contains(term)) score += 0.025;
                    return new ResearchDtos.SearchItem(item.source(), item.sourceId(), item.itemType(), item.title(), item.authors(),
                            item.publicationYear(), item.venue(), item.abstractText(), item.doi(), item.sourceUrl(), item.openAccessUrl(),
                            item.citationCount(), Math.min(1.5, score));
                }).sorted(Comparator.comparingDouble(ResearchDtos.SearchItem::score).reversed()).toList();
    }

    private ResearchDtos.SearchItem merge(ResearchDtos.SearchItem first, ResearchDtos.SearchItem second) {
        var richer = length(second.abstractText()) > length(first.abstractText()) ? second : first;
        var authors = first.authors() == null || first.authors().isEmpty() ? second.authors() : first.authors();
        return new ResearchDtos.SearchItem(first.source(), first.sourceId(), first.itemType(), first.title(), authors,
                first.publicationYear() == null ? second.publicationYear() : first.publicationYear(),
                first.venue() == null ? second.venue() : first.venue(), richer.abstractText(),
                first.doi() == null ? second.doi() : first.doi(), first.sourceUrl() == null ? second.sourceUrl() : first.sourceUrl(),
                first.openAccessUrl() == null ? second.openAccessUrl() : first.openAccessUrl(),
                max(first.citationCount(), second.citationCount()), Math.max(first.score(), second.score()) + 0.05);
    }

    private Integer max(Integer a, Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
    }

    private int length(String value) { return value == null ? 0 : value.length(); }

    private void validateYears(ResearchDtos.SearchRequest request) {
        if (request.yearFrom() != null && request.yearTo() != null && request.yearFrom() > request.yearTo()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_YEAR_RANGE", "起始年份不能晚于结束年份");
        }
    }

    private String cacheKey(ResearchDtos.SearchRequest request, List<ResearchProvider> selected) {
        try {
            var raw = toJson(request) + selected.stream().map(ResearchProvider::source).sorted().toList();
            var digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return "henan:research:search:" + java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            return "henan:research:search:" + Math.abs(request.hashCode());
        }
    }

    private CachedPayload readCache(String key) {
        try {
            var value = redisTemplate.opsForValue().get(key);
            return value == null ? null : objectMapper.readValue(value, CachedPayload.class);
        } catch (Exception ignored) { return null; }
    }

    private void writeCache(String key, CachedPayload payload) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(payload),
                    Duration.ofMinutes(Math.max(1, properties.research().cacheMinutes())));
        } catch (Exception ignored) { }
    }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { return "{}"; }
    }

    private ResearchDtos.SavedView view(ResearchSavedItem item) {
        List<String> authors;
        try { authors = objectMapper.readValue(item.getAuthors() == null ? "[]" : item.getAuthors(), new TypeReference<>() {}); }
        catch (Exception ignored) { authors = List.of(); }
        return new ResearchDtos.SavedView(item.getId(), item.getSource(), item.getSourceId(), item.getItemType(), item.getTitle(),
                authors, item.getPublicationYear(), item.getVenue(), item.getAbstractText(), item.getDoi(), item.getSourceUrl(),
                item.getOpenAccessUrl(), item.getCitationCount(), item.getCreatedAt());
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        var normalized = value.trim();
        return normalized.length() > max ? normalized.substring(0, max) : normalized;
    }

    private record ProviderOutcome(List<ResearchDtos.SearchItem> items, String warning) {}
    private record CachedPayload(List<ResearchDtos.SearchItem> items, List<String> warnings) {}
}
