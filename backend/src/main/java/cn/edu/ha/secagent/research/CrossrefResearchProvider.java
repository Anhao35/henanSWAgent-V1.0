package cn.edu.ha.secagent.research;

import cn.edu.ha.secagent.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class CrossrefResearchProvider implements ResearchProvider {
    private final WebClient.Builder webClientBuilder;
    private final AppProperties properties;

    @Override public String source() { return "CROSSREF"; }
    @Override public boolean supports(String itemType) { return true; }

    @Override
    public List<ResearchDtos.SearchItem> search(ResearchDtos.SearchRequest request) {
        if (request.openAccessOnly()) return List.of();
        var research = properties.research();
        var client = webClientBuilder.baseUrl("https://api.crossref.org").build();
        JsonNode root = client.get().uri(builder -> {
                    builder.path("/works")
                            .queryParam("query.bibliographic", request.query().trim())
                            .queryParam("rows", Math.min(30, request.normalizedLimit()))
                            .queryParam("select", "DOI,title,author,published,container-title,URL,abstract,type,is-referenced-by-count");
                    var filters = new ArrayList<String>();
                    if (request.yearFrom() != null) filters.add("from-pub-date:" + request.yearFrom() + "-01-01");
                    if (request.yearTo() != null) filters.add("until-pub-date:" + request.yearTo() + "-12-31");
                    if ("BOOK".equals(request.normalizedType())) filters.add("type:book");
                    if (!filters.isEmpty()) builder.queryParam("filter", String.join(",", filters));
                    if (research.crossrefMailto() != null && !research.crossrefMailto().isBlank()) {
                        builder.queryParam("mailto", research.crossrefMailto());
                    }
                    return builder.build();
                }).retrieve().bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(research.timeoutSeconds()));
        if (root == null) return List.of();
        var results = root.path("message").path("items");
        var items = new ArrayList<ResearchDtos.SearchItem>();
        for (int i = 0; i < results.size(); i++) {
            var node = results.get(i);
            var crossrefType = ProviderSupport.text(node, "type");
            var isBook = crossrefType != null && (crossrefType.contains("book") || crossrefType.equals("monograph"));
            if ("BOOK".equals(request.normalizedType()) && !isBook) continue;
            var authors = new ArrayList<String>();
            node.path("author").forEach(author -> {
                var name = ((ProviderSupport.text(author, "given") == null ? "" : ProviderSupport.text(author, "given") + " ")
                        + (ProviderSupport.text(author, "family") == null ? "" : ProviderSupport.text(author, "family"))).trim();
                if (!name.isBlank()) authors.add(name);
            });
            Integer year = null;
            var parts = node.path("published").path("date-parts");
            if (parts.isArray() && !parts.isEmpty() && parts.get(0).isArray() && !parts.get(0).isEmpty()) year = parts.get(0).get(0).asInt();
            var doi = ProviderSupport.text(node, "DOI");
            items.add(new ResearchDtos.SearchItem(source(), doi == null ? ProviderSupport.text(node, "URL") : doi,
                    isBook ? "BOOK" : "PAPER", ProviderSupport.firstText(node.path("title")), authors, year,
                    ProviderSupport.firstText(node.path("container-title")),
                    ProviderSupport.cleanAbstract(ProviderSupport.text(node, "abstract")), doi,
                    ProviderSupport.text(node, "URL"), null,
                    ProviderSupport.integer(node, "is-referenced-by-count"), ProviderSupport.positionScore(i, results.size())));
        }
        return items;
    }
}
