package cn.edu.ha.secagent.evidence;

import cn.edu.ha.secagent.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

@Service
public class EvidenceGateway {
    private static final Pattern DOMAIN = Pattern.compile("(?i)^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");
    private static final Pattern CVE = Pattern.compile("(?i)^CVE-\\d{4}-\\d{4,}$");
    private static final Pattern HASH = Pattern.compile("(?i)^(?:[a-f0-9]{32}|[a-f0-9]{40}|[a-f0-9]{64})$");
    private static final Pattern IPV4_SHAPE = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");
    private final List<EvidenceProvider> providers;

    public EvidenceGateway(List<EvidenceProvider> providers) {
        this.providers = providers.stream().sorted(Comparator.comparing(EvidenceProvider::source)).toList();
    }

    public String normalize(String type, String rawIndicator) {
        var value = rawIndicator == null ? "" : rawIndicator.trim();
        if (value.isBlank()) throw invalid("请输入查询目标");
        return switch (type) {
            case "ip" -> normalizeIp(value);
            case "domain" -> normalizeDomain(value);
            case "url" -> normalizeUrl(value);
            case "cve" -> CVE.matcher(value).matches() ? value.toUpperCase(Locale.ROOT) : fail("CVE 格式不正确，例如 CVE-2021-44228");
            case "hash" -> HASH.matcher(value).matches() ? value.toLowerCase(Locale.ROOT) : fail("仅支持 MD5、SHA-1 或 SHA-256 十六进制哈希");
            default -> throw invalid("不支持的指标类型");
        };
    }

    public EvidenceDtos.Bundle query(String type, String normalizedIndicator) {
        var selected = providers.stream().filter(EvidenceProvider::configured).filter(p -> p.supports(type)).toList();
        var futures = selected.stream()
                .map(provider -> CompletableFuture.supplyAsync(() -> provider.query(type, normalizedIndicator)))
                .toList();
        var results = new ArrayList<EvidenceDtos.Item>(futures.size());
        for (var future : futures) {
            try { results.add(future.join()); }
            catch (Exception ignored) { results.add(EvidenceDtos.Item.failed("未知来源", "PROVIDER_UNAVAILABLE", "来源调用失败", "")); }
        }
        return new EvidenceDtos.Bundle("evidence-bundle/v1", type, normalizedIndicator, Instant.now(), List.copyOf(results));
    }

    public String renderMarkdown(EvidenceDtos.Bundle bundle) {
        var out = new StringBuilder("## 平台统一证据包\n\n")
                .append("> Spring Boot 通过固定只读接口查询公开情报源。单一来源未检出或请求失败，都不能单独证明目标安全。\n\n")
                .append("| 来源 | 查询状态 | 证据判断 | 摘要 |\n|---|---|---|---|\n");
        for (var item : bundle.sources()) {
            String status = item.querySuccess() ? item.found() ? "命中" : "未检出" : "查询失败";
            out.append("| ").append(cell(item.source())).append(" | ").append(status).append(" | ")
                    .append(cell(item.verdict())).append(" | ").append(cell(item.summary())).append(" |\n");
        }
        for (var item : bundle.sources()) {
            if (!item.found() || item.attributes().isEmpty()) continue;
            out.append("\n### ").append(item.source()).append(" 关键字段\n\n");
            for (var entry : item.attributes().entrySet()) {
                out.append("- **").append(cell(entry.getKey())).append("**：")
                        .append(cell(String.valueOf(entry.getValue()))).append("\n");
            }
            if (item.sourceUrl() != null && !item.sourceUrl().isBlank())
                out.append("- **来源页面**：[打开官方来源](").append(item.sourceUrl()).append(")\n");
        }
        out.append("\n证据源统计：成功查询 ").append(bundle.successfulSources()).append("，命中 ")
                .append(bundle.matchedSources()).append("，失败 ").append(bundle.failedSources()).append("。\n");
        return out.toString();
    }

    private static String normalizeIp(String value) {
        if (!IPV4_SHAPE.matcher(value).matches() && !value.contains(":")) return fail("IP 格式不正确");
        try {
            var address = InetAddress.getByName(value);
            if (blockedAddress(address))
                return fail("为避免将内网资产发送至第三方，禁止查询本地、内网、链路本地或组播地址");
            return address.getHostAddress();
        } catch (Exception ignored) { return fail("IP 格式不正确"); }
    }

    private static String normalizeDomain(String value) {
        var domain = value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
        try { domain = IDN.toASCII(domain).toLowerCase(Locale.ROOT); }
        catch (Exception ignored) { return fail("域名格式不正确"); }
        if (!DOMAIN.matcher(domain).matches() || blockedHost(domain)) return fail("域名格式不正确或属于本地域名");
        return domain;
    }

    private static String normalizeUrl(String value) {
        try {
            var uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null)
                return fail("URL 必须是完整的 http 或 https 地址");
            var host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT);
            if (blockedHost(host)) return fail("为避免外发内网资产，禁止查询本地或内网 URL");
            if (IPV4_SHAPE.matcher(host).matches() || host.contains(":")) normalizeIp(host);
            return uri.toASCIIString();
        } catch (ApiException exception) { throw exception; }
        catch (Exception ignored) { return fail("URL 格式不正确"); }
    }

    private static boolean blockedHost(String host) {
        return "localhost".equals(host) || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || !host.contains(".");
    }

    private static boolean blockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        var bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = Byte.toUnsignedInt(bytes[0]), b = Byte.toUnsignedInt(bytes[1]), c = Byte.toUnsignedInt(bytes[2]);
            return a == 0 || (a == 100 && b >= 64 && b <= 127)
                    || (a == 192 && b == 0 && (c == 0 || c == 2))
                    || (a == 198 && (b == 18 || b == 19))
                    || (a == 198 && b == 51 && c == 100)
                    || (a == 203 && b == 0 && c == 113) || a >= 240;
        }
        int first = Byte.toUnsignedInt(bytes[0]), second = Byte.toUnsignedInt(bytes[1]);
        boolean uniqueLocal = (first & 0xfe) == 0xfc;
        boolean documentation = first == 0x20 && second == 0x01 && Byte.toUnsignedInt(bytes[2]) == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8;
        return uniqueLocal || documentation;
    }

    private static String cell(String value) {
        if (value == null) return "";
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ").replace("`", "'");
    }

    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INDICATOR", message); }
    private static String fail(String message) { throw invalid(message); }
}
