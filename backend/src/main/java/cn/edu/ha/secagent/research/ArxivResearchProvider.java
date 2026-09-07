package cn.edu.ha.secagent.research;

import cn.edu.ha.secagent.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ArxivResearchProvider implements ResearchProvider {
    private final WebClient.Builder webClientBuilder;
    private final AppProperties properties;

    @Override public String source() { return "ARXIV"; }
    @Override public boolean supports(String itemType) { return !"BOOK".equals(itemType); }

    @Override
    public List<ResearchDtos.SearchItem> search(ResearchDtos.SearchRequest request) {
        var query = buildQuery(request.query());
        var xml = webClientBuilder.baseUrl("https://export.arxiv.org").build().get()
                .uri(builder -> builder.path("/api/query")
                        .queryParam("search_query", query)
                        .queryParam("start", 0)
                        .queryParam("max_results", Math.min(25, request.normalizedLimit()))
                        .queryParam("sortBy", "relevance").build())
                .retrieve().bodyToMono(String.class)
                .block(Duration.ofSeconds(properties.research().timeoutSeconds()));
        if (xml == null || xml.isBlank()) return List.of();
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            var entries = document.getElementsByTagNameNS("*", "entry");
            var items = new ArrayList<ResearchDtos.SearchItem>();
            for (int i = 0; i < entries.getLength(); i++) {
                var entry = (Element) entries.item(i);
                var published = childText(entry, "published");
                Integer year = published == null ? null : OffsetDateTime.parse(published).getYear();
                if (request.yearFrom() != null && year != null && year < request.yearFrom()) continue;
                if (request.yearTo() != null && year != null && year > request.yearTo()) continue;
                var authors = new ArrayList<String>();
                var authorNodes = entry.getElementsByTagNameNS("*", "author");
                for (int a = 0; a < authorNodes.getLength(); a++) authors.add(childText((Element) authorNodes.item(a), "name"));
                var sourceUrl = childText(entry, "id");
                String pdfUrl = null;
                var links = entry.getElementsByTagNameNS("*", "link");
                for (int l = 0; l < links.getLength(); l++) {
                    var link = (Element) links.item(l);
                    if ("application/pdf".equals(link.getAttribute("type")) || "pdf".equals(link.getAttribute("title"))) pdfUrl = link.getAttribute("href");
                }
                var id = sourceUrl == null ? null : sourceUrl.replaceFirst("(?i)^https?://arxiv\\.org/abs/", "");
                items.add(new ResearchDtos.SearchItem(source(), id, "PAPER", childText(entry, "title"), authors, year,
                        "arXiv", ProviderSupport.cleanAbstract(childText(entry, "summary")), null,
                        sourceUrl, pdfUrl, null, ProviderSupport.positionScore(i, entries.getLength())));
            }
            return items;
        } catch (Exception exception) {
            throw new IllegalStateException("arXiv 返回数据解析失败", exception);
        }
    }

    private String buildQuery(String query) {
        var cleaned = query.trim().replaceAll("[\\r\\n]+", " ");
        if (cleaned.length() > 300) cleaned = cleaned.substring(0, 300);
        var terms = cleaned.split("\\s+");
        var fields = new ArrayList<String>();
        for (var term : terms) if (!term.isBlank()) fields.add("all:\"" + term.replace("\"", "") + "\"");
        return String.join(" AND ", fields);
    }

    private String childText(Element parent, String localName) {
        var nodes = parent.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().replaceAll("\\s+", " ").trim();
    }
}
