package cn.edu.ha.secagent.evidence;

import cn.edu.ha.secagent.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceGatewayTest {
    @Test
    void normalizesPublicIndicatorsAndBlocksPrivateTargets() {
        var gateway = new EvidenceGateway(List.of());
        assertEquals("8.8.8.8", gateway.normalize("ip", " 8.8.8.8 "));
        assertEquals("example.com", gateway.normalize("domain", "Example.COM."));
        assertEquals("CVE-2021-44228", gateway.normalize("cve", "cve-2021-44228"));
        assertEquals("https://example.com/a?q=1", gateway.normalize("url", "https://example.com/a?q=1"));
        assertThrows(ApiException.class, () -> gateway.normalize("ip", "127.0.0.1"));
        assertThrows(ApiException.class, () -> gateway.normalize("ip", "192.168.1.10"));
        assertThrows(ApiException.class, () -> gateway.normalize("ip", "100.64.1.1"));
        assertThrows(ApiException.class, () -> gateway.normalize("ip", "192.0.2.1"));
        assertThrows(ApiException.class, () -> gateway.normalize("domain", "host.internal"));
        assertThrows(ApiException.class, () -> gateway.normalize("url", "http://localhost/admin"));
        assertThrows(ApiException.class, () -> gateway.normalize("hash", "not-a-hash"));
    }

    @Test
    void bundleKeepsNotFoundSeparateFromProviderFailure() {
        EvidenceProvider notFound = provider("A", true, EvidenceDtos.Item.notFound("A", "未检出", "https://a.example"));
        EvidenceProvider failed = provider("B", true, EvidenceDtos.Item.failed("B", "TIMEOUT", "超时", "https://b.example"));
        EvidenceProvider unsupported = provider("C", false, EvidenceDtos.Item.found("C", "MATCH", "不应调用", "", Map.of()));
        var gateway = new EvidenceGateway(List.of(failed, unsupported, notFound));
        var bundle = gateway.query("ip", "8.8.8.8");
        assertEquals(2, bundle.sources().size());
        assertEquals(1, bundle.successfulSources());
        assertEquals(0, bundle.matchedSources());
        assertEquals(1, bundle.failedSources());
        assertTrue(bundle.partial());
        var markdown = gateway.renderMarkdown(bundle);
        assertTrue(markdown.contains("未检出"));
        assertTrue(markdown.contains("查询失败"));
        assertTrue(markdown.contains("不能单独证明目标安全"));
    }

    private static EvidenceProvider provider(String name, boolean supports, EvidenceDtos.Item result) {
        return new EvidenceProvider() {
            public String source() { return name; }
            public boolean supports(String type) { return supports; }
            public boolean configured() { return true; }
            public EvidenceDtos.Item query(String type, String indicator) { return result; }
        };
    }
}
