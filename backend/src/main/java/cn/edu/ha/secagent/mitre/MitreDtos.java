package cn.edu.ha.secagent.mitre;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class MitreDtos {
    private MitreDtos() {}

    public record MappingRequest(
            @NotBlank @Size(max = 30000) String description,
            @NotBlank @Pattern(regexp = "(?i)ATTACK|CWE", message = "仅支持 ATTACK 或 CWE") String type
    ) {}

    public record AttackMapping(
            Integer behaviorOrder,
            String tacticId,
            String tacticName,
            String techniqueId,
            String techniqueName,
            double confidence,
            String evidence,
            String reasoning,
            boolean identifierFormatValid,
            boolean evidenceMatched
    ) {}

    public record CweMapping(
            String cweId,
            String cweName,
            double confidence,
            String evidence,
            String reasoning,
            boolean identifierFormatValid,
            boolean evidenceMatched
    ) {}

    public record MappingResponse(
            UUID id,
            String type,
            String summary,
            List<String> attackBehaviors,
            List<AttackMapping> attackMappings,
            List<CweMapping> cweMappings,
            List<String> unmappedObservations,
            String model,
            boolean cached,
            Integer promptTokens,
            Integer completionTokens,
            LocalDateTime createdAt
    ) {}

    public record HistoryView(
            UUID id,
            String type,
            String inputPreview,
            String summary,
            int mappingCount,
            String status,
            String model,
            LocalDateTime createdAt
    ) {}

    record LlmResult(
            String summary,
            List<String> attackBehaviors,
            List<LlmAttackMapping> attackMappings,
            List<LlmCweMapping> cweMappings,
            List<String> unmappedObservations
    ) {}

    record LlmAttackMapping(
            Integer behaviorOrder,
            String tacticId,
            String tacticName,
            String techniqueId,
            String techniqueName,
            Double confidence,
            String evidence,
            String reasoning
    ) {}

    record LlmCweMapping(
            String cweId,
            String cweName,
            Double confidence,
            String evidence,
            String reasoning
    ) {}

    record StoredResult(
            String summary,
            List<String> attackBehaviors,
            List<AttackMapping> attackMappings,
            List<CweMapping> cweMappings,
            List<String> unmappedObservations
    ) {}
}
