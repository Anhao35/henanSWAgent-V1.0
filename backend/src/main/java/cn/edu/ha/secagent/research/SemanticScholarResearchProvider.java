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
public class SemanticScholarResearchProvider implements ResearchProvider {
    private final WebClient.Builder webClientBuilder;
    private final AppProperties properties;

    @Override public String source() { return "SEMANTIC_SCHOLAR"; }
    @Override public boolean available() {
        var key = properties.research().semanticScholarApiKey();
        return key != null && !key.isBlank();
    }
    @Override public boolean supports(String itemType) { return !"BOOK".equals(itemType); }

    @Override
    public List<ResearchDtos.SearchItem> search(ResearchDtos.SearchRequest request) {
        var key = properties.research().semanticScholarApiKey();
        var call = webClientBuilder.baseUrl("https://api.semanticscholar.org").build().get()
                .uri(builder -> {
                    builder.path("/graph/v1/paper/search")
                            .queryParam("query", request.query().trim())
                            .queryParam("limit", Math.min(30, request.normalizedLimit()))
                            .queryParam("fields", "paperId,title,abstract,authors,year,venue,url,externalIds,citationCount,openAccessPdf");
                    if (request.yearFrom() != null || request.yearTo() != null) {
                        builder.queryParam("year", (request.yearFrom() == null ? "" : request.yearFrom()) + "-" + (request.yearTo() == null ? "" : request.yearTo()));
                    }
                    return builder.build();
                });
        if (key != null && !key.isBlank()) call = call.header("x-api-key", key);
        JsonNode root = call.retrieve().bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(properties.research().timeoutSeconds()));
        if (root == null) return List.of();
        var results = root.path("data");
        var items = new ArrayList<ResearchDtos.SearchItem>();
        for (int i = 0; i < results.size(); i++) {
            var node = results.get(i);
            var oa = ProviderSupport.text(node.path("openAccessPdf"), "url");
            if (request.openAccessOnly() && oa == null) continue;
            var authors = new ArrayList<String>();
            node.path("authors").forEach(author -> {
                var name = ProviderSupport.text(author, "name");
                if (name != null) authors.add(name);
            });
            var ids = node.path("externalIds");
            items.add(new ResearchDtos.SearchItem(source(), ProviderSupport.text(node, "paperId"), "PAPER",
                    ProviderSupport.text(node, "title"), authors, ProviderSupport.integer(node, "year"),
                    ProviderSupport.text(node, "venue"), ProviderSupport.cleanAbstract(ProviderSupport.text(node, "abstract")),
                    ProviderSupport.text(ids, "DOI"), ProviderSupport.text(node, "url"), oa,
                    ProviderSupport.integer(node, "citationCount"), ProviderSupport.positionScore(i, results.size())));
        }
        return items;
    }
}
