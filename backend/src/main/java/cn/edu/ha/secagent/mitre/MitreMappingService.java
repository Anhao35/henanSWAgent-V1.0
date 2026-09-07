package cn.edu.ha.secagent.mitre;

import cn.edu.ha.secagent.common.ApiException;
import cn.edu.ha.secagent.config.AppProperties;
import cn.edu.ha.secagent.domain.MitreMappingRun;
import cn.edu.ha.secagent.repository.MitreMappingRunRepository;
import cn.edu.ha.secagent.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class MitreMappingService {
    private static final Pattern ATTACK_TECHNIQUE = Pattern.compile("^T\\d{4}(?:\\.\\d{3})?$");
    private static final Pattern ATTACK_TACTIC = Pattern.compile("^TA\\d{4}$");
    private static final Pattern CWE = Pattern.compile("^CWE-\\d{1,6}$");

    private final MitreMappingRunRepository runRepository;
    private final UserRepository userRepository;
    private final DeepSeekMitreClient client;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MitreResultParser resultParser;
    private final MitreKnowledgeCatalog knowledgeCatalog;
    private final AppProperties properties;

    public MitreDtos.MappingResponse map(UUID userId, MitreDtos.MappingRequest request) {
        var type = request.type().trim().toUpperCase(Locale.ROOT);
        var description = request.description().trim();
        if (description.length() < 20) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DESCRIPTION_TOO_SHORT", "请提供至少 20 个字符的攻击或漏洞描述");
        }
        enforceRateLimit(userId);

        var inputHash = sha256(description);
        var cacheHours = Math.max(1, properties.deepSeek().cacheHours());
        var cached = runRepository.findFirstByUserIdAndMappingTypeAndInputHashAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                userId, type, inputHash, "COMPLETED", LocalDateTime.now().minusHours(cacheHours));
        if (cached.isPresent()) return response(cached.get(), readStored(cached.get()), true);

        var run = new MitreMappingRun();
        run.setUser(userRepository.getReferenceById(userId));
        run.setMappingType(type);
        run.setInputText(description);
        run.setInputHash(inputHash);
        run.setModel(properties.deepSeek().model());
        run.setStatus("RUNNING");
        runRepository.save(run);

        try {
            var providerResult = client.map(userId, type, description);
            var parsedResult = parseWithRepair(run, userId, type, description, providerResult);
            var parsed = parsedResult.parsed();
            var stored = normalize(type, description, parsed);
            run.setResultJson(objectMapper.writeValueAsString(stored));
            run.setPromptTokens(parsedResult.promptTokens());
            run.setCompletionTokens(parsedResult.completionTokens());
            run.setStatus("COMPLETED");
            runRepository.save(run);
            return response(run, stored, false);
        } catch (ApiException exception) {
            if (!"FAILED".equals(run.getStatus())) fail(run, exception.getMessage());
            throw exception;
        } catch (Exception exception) {
            fail(run, "模型返回的 JSON 结构无法解析");
            throw new ApiException(HttpStatus.BAD_GATEWAY, "INVALID_MAPPING_RESULT", "模型返回结果格式异常，请重试");
        }
    }

    private ParsedResult parseWithRepair(MitreMappingRun run, UUID userId, String type, String description,
                                         DeepSeekMitreClient.ClientResult first) {
        try {
            return new ParsedResult(resultParser.parse(first.json()), first.promptTokens(), first.completionTokens());
        } catch (Exception firstFailure) {
            log.warn("MITRE mapping response requires repair: runId={}, type={}, cause={}: {}",
                    run.getId(), type, firstFailure.getClass().getSimpleName(), diagnosticMessage(firstFailure));
            DeepSeekMitreClient.ClientResult repaired;
            try {
                repaired = client.repair(userId, type, description, first.json());
            } catch (ApiException repairCallFailure) {
                fail(run, "初次响应结构异常，自动修复请求失败：" + repairCallFailure.getMessage());
                throw repairCallFailure;
            }
            try {
                return new ParsedResult(resultParser.parse(repaired.json()),
                        add(first.promptTokens(), repaired.promptTokens()),
                        add(first.completionTokens(), repaired.completionTokens()));
            } catch (Exception repairParseFailure) {
                var diagnostic = "初次响应结构异常，自动修复后仍无法解析（"
                        + repairParseFailure.getClass().getSimpleName() + "：" + diagnosticMessage(repairParseFailure) + "）";
                fail(run, diagnostic);
                log.warn("MITRE mapping repair failed: runId={}, type={}, cause={}: {}",
                        run.getId(), type, repairParseFailure.getClass().getSimpleName(), diagnosticMessage(repairParseFailure));
                throw new ApiException(HttpStatus.BAD_GATEWAY, "INVALID_MAPPING_RESULT", "模型返回结果格式异常，自动修复未成功，请重试");
            }
        }
    }

    public List<MitreDtos.HistoryView> history(UUID userId, int limit) {
        return runRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, Math.max(1, Math.min(limit, 100))))
                .stream().map(run -> {
                    MitreDtos.StoredResult stored = null;
                    if ("COMPLETED".equals(run.getStatus())) {
                        try { stored = readStored(run); } catch (Exception ignored) { }
                    }
                    var summary = stored == null ? (run.getErrorMessage() == null ? "映射处理中" : run.getErrorMessage()) : stored.summary();
                    var count = stored == null ? 0 : stored.attackMappings().size() + stored.cweMappings().size();
                    return new MitreDtos.HistoryView(run.getId(), run.getMappingType(), preview(run.getInputText()), summary,
                            count, run.getStatus(), run.getModel(), run.getCreatedAt());
                }).toList();
    }

    public MitreDtos.MappingResponse detail(UUID userId, UUID id) {
        var run = runRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MAPPING_NOT_FOUND", "映射记录不存在"));
        if (!"COMPLETED".equals(run.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "MAPPING_NOT_COMPLETED", "该映射尚未成功完成");
        }
        return response(run, readStored(run), true);
    }

    private MitreDtos.StoredResult normalize(String type, String source, MitreDtos.LlmResult raw) {
        var behaviors = cleanStrings(raw.attackBehaviors(), 30, 600);
        var observations = cleanStrings(raw.unmappedObservations(), 30, 600);
        var attacks = new ArrayList<MitreDtos.AttackMapping>();
        var cwes = new ArrayList<MitreDtos.CweMapping>();

        if ("ATTACK".equals(type) && raw.attackMappings() != null) {
            for (var item : raw.attackMappings().stream().limit(12).toList()) {
                if (item == null) continue;
                var techniqueId = upper(item.techniqueId());
                var tacticId = upper(item.tacticId());
                if (techniqueId.isBlank()) continue;
                var techniqueValid = ATTACK_TECHNIQUE.matcher(techniqueId).matches()
                        && knowledgeCatalog.containsTechnique(techniqueId);
                var tacticValid = tacticId.isBlank() || (ATTACK_TACTIC.matcher(tacticId).matches()
                        && knowledgeCatalog.containsTactic(tacticId));
                attacks.add(new MitreDtos.AttackMapping(item.behaviorOrder(), tacticId,
                        knowledgeCatalog.attackName(tacticId).orElse(clean(item.tacticName(), 160)), techniqueId,
                        knowledgeCatalog.attackName(techniqueId).orElse(clean(item.techniqueName(), 200)),
                        confidence(item.confidence()), clean(item.evidence(), 800), clean(item.reasoning(), 1200),
                        techniqueValid && tacticValid, evidenceMatched(source, item.evidence())));
            }
        }
        if ("CWE".equals(type) && raw.cweMappings() != null) {
            for (var item : raw.cweMappings().stream().limit(12).toList()) {
                if (item == null) continue;
                var cweId = upper(item.cweId());
                if (cweId.isBlank()) continue;
                cwes.add(new MitreDtos.CweMapping(cweId,
                        knowledgeCatalog.cweName(cweId).orElse(clean(item.cweName(), 240)), confidence(item.confidence()),
                        clean(item.evidence(), 800), clean(item.reasoning(), 1200),
                        CWE.matcher(cweId).matches() && knowledgeCatalog.containsCwe(cweId),
                        evidenceMatched(source, item.evidence())));
            }
        }
        return new MitreDtos.StoredResult(clean(raw.summary(), 2000), behaviors, attacks, cwes, observations);
    }

    private MitreDtos.MappingResponse response(MitreMappingRun run, MitreDtos.StoredResult stored, boolean cached) {
        return new MitreDtos.MappingResponse(run.getId(), run.getMappingType(), stored.summary(), stored.attackBehaviors(),
                stored.attackMappings(), stored.cweMappings(), stored.unmappedObservations(), run.getModel(), cached,
                run.getPromptTokens(), run.getCompletionTokens(), run.getCreatedAt());
    }

    private MitreDtos.StoredResult readStored(MitreMappingRun run) {
        try {
            return objectMapper.readValue(run.getResultJson(), MitreDtos.StoredResult.class);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MAPPING_RESULT_DAMAGED", "映射记录内容无法读取");
        }
    }

    private void fail(MitreMappingRun run, String message) {
        run.setStatus("FAILED");
        run.setErrorMessage(clean(message, 500));
        runRepository.save(run);
    }

    private void enforceRateLimit(UUID userId) {
        try {
            var key = "henan:mitre:rate:" + userId + ":" + (System.currentTimeMillis() / 60000);
            var count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) redisTemplate.expire(key, Duration.ofMinutes(2));
            if (count != null && count > Math.max(1, properties.deepSeek().requestsPerMinute())) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "MITRE_RATE_LIMIT", "映射请求过于频繁，请一分钟后再试");
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception ignored) {
            // Redis 短暂不可用时不阻断核心映射能力。
        }
    }

    private boolean evidenceMatched(String source, String evidence) {
        if (evidence == null || evidence.isBlank()) return false;
        var normalizedEvidence = normalizeText(evidence);
        return normalizedEvidence.length() >= 4 && normalizeText(source).contains(normalizedEvidence);
    }

    private String normalizeText(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。；：！？、“”‘’（）【】]+", "");
    }

    private List<String> cleanStrings(List<String> values, int maxItems, int maxLength) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank()).limit(maxItems)
                .map(value -> clean(value, maxLength)).toList();
    }

    private double confidence(Double value) {
        return value == null ? 0 : Math.max(0, Math.min(1, value));
    }

    private String upper(String value) {
        return value == null ? "" : clean(value, 32).toUpperCase(Locale.ROOT);
    }

    private String clean(String value, int maxLength) {
        if (value == null) return "";
        var cleaned = value.trim();
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }

    private String preview(String value) {
        var cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 160 ? cleaned.substring(0, 160) + "…" : cleaned;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Integer add(Integer left, Integer right) {
        if (left == null) return right;
        if (right == null) return left;
        return left + right;
    }

    private String diagnosticMessage(Exception exception) {
        var message = exception.getMessage();
        if (message == null || message.isBlank()) return "无详细信息";
        message = message.replaceAll("[\\r\\n]+", " ").trim();
        return message.length() > 260 ? message.substring(0, 260) + "…" : message;
    }

    private record ParsedResult(MitreDtos.LlmResult parsed, Integer promptTokens, Integer completionTokens) {}
}
