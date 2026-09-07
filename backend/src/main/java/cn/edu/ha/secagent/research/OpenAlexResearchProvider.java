package cn.edu.ha.secagent.research;

import cn.edu.ha.secagent.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OpenAlexResearchProvider implements ResearchProvider {
    private final WebClient.Builder webClientBuilder;
    private final AppProperties properties;

    @Override public String source() { return "OPENALEX"; }
    @Override public boolean available() {
        var key = properties.research().openAlexApiKey();
        return key != null && !key.isBlank();
    }
    @Override public boolean supports(String itemType) { return !"BOOK".equals(itemType); }

    @Override
    public List<ResearchDtos.SearchItem> search(ResearchDtos.SearchRequest request) {
        var research = properties.research();
        var key = research.openAlexApiKey();
        var client = webClientBuilder.baseUrl("https://api.openalex.org")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .defaultHeader(HttpHeaders.USER_AGENT, "HERCERT-Security-Research/1.0").build();
        JsonNode root = client.get().uri(builder -> {
                    builder.path("/works")
                            .queryParam("search", request.query().trim())
                            .queryParam("per_page", Math.min(30, request.normalizedLimit()));
                    var filters = new ArrayList<String>();
                    if (request.yearFrom() != null) filters.add("from_publication_date:" + request.yearFrom() + "-01-01");
                    if (request.yearTo() != null) filters.add("to_publication_date:" + request.yearTo() + "-12-31");
                    if (request.openAccessOnly()) filters.add("open_access.is_oa:true");
                    if (!filters.isEmpty()) builder.queryParam("filter", String.join(",", filters));
                    return builder.build();
                }).retrieve().bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(research.timeoutSeconds()));
        if (root == null) return List.of();
        var results = root.path("results");
        var items = new ArrayList<ResearchDtos.SearchItem>();
        for (int i = 0; i < results.size(); i++) {
            var node = results.get(i);
            var authors = new ArrayList<String>();
            node.path("authorships").forEach(authorship -> {
                var name = ProviderSupport.text(authorship.path("author"), "display_name");
                if (name != null) authors.add(name);
            });
            var primary = node.path("primary_location");
            var oaUrl = ProviderSupport.text(node.path("open_access"), "oa_url");
            if (oaUrl == null) oaUrl = ProviderSupport.text(primary, "pdf_url");
            var sourceUrl = ProviderSupport.text(primary, "landing_page_url");
            if (sourceUrl == null) sourceUrl = ProviderSupport.text(node, "id");
            var doi = ProviderSupport.text(node, "doi");
            if (doi != null) doi = doi.replaceFirst("(?i)^https?://doi\\.org/", "");
            var id = ProviderSupport.text(node, "id");
            items.add(new ResearchDtos.SearchItem(source(), id, "PAPER",
                    ProviderSupport.text(node, "display_name"), authors,
                    ProviderSupport.integer(node, "publication_year"),
                    ProviderSupport.text(primary.path("source"), "display_name"),
                    invertedAbstract(node.path("abstract_inverted_index")), doi, sourceUrl, oaUrl,
                    ProviderSupport.integer(node, "cited_by_count"), ProviderSupport.positionScore(i, results.size())));
        }
        return items;
    }

    private String invertedAbstract(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        var positions = new HashMap<Integer, String>();
        node.fields().forEachRemaining(entry -> entry.getValue().forEach(position -> positions.put(position.asInt(), entry.getKey())));
        var words = positions.entrySet().stream().sorted(Comparator.comparingInt(java.util.Map.Entry::getKey))
                .map(java.util.Map.Entry::getValue).toList();
        return ProviderSupport.cleanAbstract(String.join(" ", words));
    }
}
