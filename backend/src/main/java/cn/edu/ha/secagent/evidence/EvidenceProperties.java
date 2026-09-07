package cn.edu.ha.secagent.evidence;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.evidence")
public record EvidenceProperties(
        int timeoutSeconds,
        int abuseIpDbMaxAgeDays,
        String abuseIpDbApiKey,
        String abuseChAuthKey,
        String nvdApiKey,
        String greyNoiseApiKey,
        String otxApiKey,
        Endpoints endpoints
) {
    public record Endpoints(
            String abuseIpDb,
            String threatFox,
            String nvd,
            String greyNoise,
            String otx,
            String epss,
            String cisaKev,
            String circlHashlookup
    ) {}
}
