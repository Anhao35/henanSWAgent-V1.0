package cn.edu.ha.secagent.research;

import cn.edu.ha.secagent.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OpenLibraryResearchProvider implements ResearchProvider {
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    @Override public String source() { return "OPEN_LIBRARY"; }
    @Override public boolean available() { return properties.research().openLibraryEnabled(); }
    @Override public boolean supports(String itemType) { return !"PAPER".equals(itemType); }

    @Override
    public List<ResearchDtos.SearchItem> search(ResearchDtos.SearchRequest request) {
        var timeout = Duration.ofSeconds(properties.research().timeoutSeconds());
        var fields = "key,title,author_name,first_publish_year,publisher,isbn,ebook_access,public_scan_b";
        var url = "https://openlibrary.org/search.json?q=" + URLEncoder.encode(request.query().trim(), StandardCharsets.UTF_8)
                + "&limit=" + Math.min(30, request.normalizedLimit())
                + "&fields=" + URLEncoder.encode(fields, StandardCharsets.UTF_8);
        try {
            var client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
            var httpRequest = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("User-Agent", "HERCERT-Research/1.0").GET().build();
            var httpResponse = client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                throw new IllegalStateException("Open Library HTTP " + httpResponse.statusCode());
            }
            return parse(request, objectMapper.readTree(httpResponse.body()));
        } catch (Exception exception) {
            throw new IllegalStateException("Open Library 查询失败", exception);
        }
    }

    private List<ResearchDtos.SearchItem> parse(ResearchDtos.SearchRequest request, JsonNode root) {
        if (root == null) return List.of();
        var results = root.path("docs");
        var items = new ArrayList<ResearchDtos.SearchItem>();
        for (int i = 0; i < results.size(); i++) {
            var node = results.get(i);
            var key = ProviderSupport.text(node, "key");
            var year = ProviderSupport.integer(node, "first_publish_year");
            if (request.yearFrom() != null && year != null && year < request.yearFrom()) continue;
            if (request.yearTo() != null && year != null && year > request.yearTo()) continue;
            var publicScan = node.path("public_scan_b").asBoolean(false) || "public".equals(ProviderSupport.text(node, "ebook_access"));
            if (request.openAccessOnly() && !publicScan) continue;
            var url = key == null ? "https://openlibrary.org/search?q=" + request.query() : "https://openlibrary.org" + key;
            items.add(new ResearchDtos.SearchItem(source(), key, "BOOK", ProviderSupport.text(node, "title"),
                    ProviderSupport.textArray(node.path("author_name")), year,
                    ProviderSupport.firstText(node.path("publisher")), null,
                    ProviderSupport.firstText(node.path("isbn")), url, publicScan ? url : null,
                    null, ProviderSupport.positionScore(i, results.size())));
        }
        return items;
    }
}
