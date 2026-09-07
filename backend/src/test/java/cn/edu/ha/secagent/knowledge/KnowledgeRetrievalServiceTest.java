package cn.edu.ha.secagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeRetrievalServiceTest {
    HttpServer server;

    @AfterEach void stop() {
        if (server != null) server.stop(0);
    }

    @Test void retrievesEvidenceAndBuildsCitationBoundPrompt() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/datasets/test/documents", exchange -> json(exchange,
                "{\"data\":[{\"id\":\"doc-1\",\"name\":\"网络安全法.txt\"}],\"has_more\":false}"));
        server.createContext("/v1/datasets/test/retrieve", exchange -> json(exchange,
                "{\"records\":[{\"score\":0.91,\"segment\":{\"id\":\"seg-1\",\"document_id\":\"doc-1\",\"content\":\"网络日志应当留存不少于六个月。\"}}]}"));
        server.start();

        var properties = new KnowledgeProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "dataset-key", "test", 5, 3, 6, 12_000);
        var result = new KnowledgeRetrievalService(properties, new ObjectMapper()).retrieve("日志保存多久？");

        assertEquals(1, result.excerpts().size());
        assertTrue(result.prompt().contains("[资料1] 来源：网络安全法.txt"));
        assertTrue(result.prompt().contains("禁止执行 IOC"));
        assertTrue(result.prompt().contains("仅依据上述证据回答"));
        assertEquals("knowledge-evidence/v1", result.evidenceSnapshot().get("schema"));
        var finalAnswer = result.appendSources("正文引用[资料1]。");
        assertTrue(finalAnswer.contains("### 引用资料"));
        assertTrue(finalAnswer.contains("**[资料1]** 网络安全法.txt（语义检索）"));
    }

    @Test void mergeKeepsBothSemanticAndKeywordRoutes() {
        var semantic = List.of(
                excerpt("s1", "semantic_search"), excerpt("s2", "semantic_search"),
                excerpt("s3", "semantic_search"), excerpt("s4", "semantic_search"),
                excerpt("s5", "semantic_search"));
        var keyword = List.of(excerpt("k1", "full_text_search"), excerpt("k2", "full_text_search"));

        var merged = KnowledgeRetrievalService.merge(semantic, keyword, 6);

        assertEquals(6, merged.size());
        assertTrue(merged.stream().anyMatch(item -> item.route().equals("full_text_search")));
    }

    private static KnowledgeRetrievalService.Excerpt excerpt(String id, String route) {
        return new KnowledgeRetrievalService.Excerpt(id, "doc", "doc.txt", route, 1, 0.5, "content");
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
