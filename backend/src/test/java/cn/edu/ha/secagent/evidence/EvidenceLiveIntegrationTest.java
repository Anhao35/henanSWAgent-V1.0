package cn.edu.ha.secagent.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RUN_LIVE_EVIDENCE_TESTS", matches = "true")
class EvidenceLiveIntegrationTest {
    private final EvidenceProperties properties = new EvidenceProperties(
            20, 90,
            System.getenv("ABUSEIPDB_API_KEY"), System.getenv("ABUSECH_AUTH_KEY"),
            System.getenv("NVD_API_KEY"), System.getenv("GREYNOISE_API_KEY"), System.getenv("OTX_API_KEY"),
            new EvidenceProperties.Endpoints(
                    "https://api.abuseipdb.com/api/v2/check",
                    "https://threatfox-api.abuse.ch/api/v1/",
                    "https://services.nvd.nist.gov/rest/json/cves/2.0",
                    "https://api.greynoise.io/v3/community",
                    "https://otx.alienvault.com/api/v1/indicators",
                    "https://api.first.org/data/v1/epss",
                    "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json",
                    "https://hashlookup.circl.lu/lookup"));
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void liveProvidersProduceNormalizedEvidenceWithoutLeakingCredentials() {
        var providers = List.<EvidenceProvider>of(
                new AbuseIpDbProvider(properties, mapper), new AbuseChProvider(properties, mapper),
                new NvdProvider(properties, mapper), new GreyNoiseProvider(properties, mapper),
                new OtxProvider(properties, mapper), new EpssProvider(properties, mapper),
                new CisaKevProvider(properties, mapper), new CirclHashlookupProvider(properties, mapper));
        var gateway = new EvidenceGateway(providers);

        var ip = gateway.query("ip", "8.8.8.8");
        assertProviderSucceeded(ip, "AbuseIPDB");
        assertProviderSucceeded(ip, "abuse.ch ThreatFox");
        assertProviderSucceeded(ip, "GreyNoise"); // 404 is a successful no-record response

        var cve = gateway.query("cve", "CVE-2021-44228");
        assertProviderMatched(cve, "NIST NVD");
        assertProviderMatched(cve, "FIRST EPSS");
        assertProviderMatched(cve, "CISA KEV");

        var serialized = mapper.valueToTree(List.of(ip, cve)).toString();
        for (var name : List.of("ABUSEIPDB_API_KEY", "ABUSECH_AUTH_KEY", "NVD_API_KEY", "GREYNOISE_API_KEY", "OTX_API_KEY")) {
            var secret = System.getenv(name);
            if (secret != null && !secret.isBlank()) assertFalse(serialized.contains(secret));
        }
    }

    private static void assertProviderSucceeded(EvidenceDtos.Bundle bundle, String source) {
        var item = bundle.sources().stream().filter(value -> source.equals(value.source())).findFirst().orElseThrow();
        assertTrue(item.querySuccess(), source + " failed: " + item.errorCode());
    }

    private static void assertProviderMatched(EvidenceDtos.Bundle bundle, String source) {
        var item = bundle.sources().stream().filter(value -> source.equals(value.source())).findFirst().orElseThrow();
        assertTrue(item.querySuccess(), source + " failed: " + item.errorCode());
        assertTrue(item.found(), source + " did not return the known test CVE");
    }
}
