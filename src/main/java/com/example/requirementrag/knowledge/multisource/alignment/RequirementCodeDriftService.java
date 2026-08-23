package com.example.requirementrag.knowledge.multisource.alignment;

import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BuildResult;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BusinessConcept;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.ConceptMember;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DriftItem;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DriftReport;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DriftType;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.TruthRole;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.VersionContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 需求—代码漂移检测（Phase 4）：把需求从默认裁决者调整为可审计的意图/漂移来源。
 *
 * <p>每个需求声明映射到业务概念：有代码成员 → ALIGNED；无代码成员 → UNMAPPED；
 * 需求值与配置（参数表）值结构化不一致 → DOCUMENT_DRIFT（文档过期候选）；
 * 需求与配置一致但存在代码成员时保留 ALIGNED，不自动裁决任一侧。
 */
@Service
public class RequirementCodeDriftService {

    private final MultiSourceKnowledgeStore knowledgeStore;
    private final CodeCentricAlignmentStore alignmentStore;
    private final VersionContextService versionContextService;

    public RequirementCodeDriftService(MultiSourceKnowledgeStore knowledgeStore,
                                       CodeCentricAlignmentStore alignmentStore,
                                       VersionContextService versionContextService) {
        this.knowledgeStore = knowledgeStore;
        this.alignmentStore = alignmentStore;
        this.versionContextService = versionContextService;
    }

    /** 构建项目/版本的需求—代码漂移报告（幂等重建 Phase 4 结论）。 */
    public BuildResult build(String projectId, String version, String environment) {
        versionContextService.resolve(projectId, version, environment);
        List<KnowledgeClaimRecord> requirements = knowledgeStore.findClaimsByProjectVersion(projectId, version)
                .stream().filter(claim -> claim.sourceType() == SourceType.REQUIREMENT).toList();
        List<ParameterClaim> parameters = knowledgeStore.findParameters(projectId, version);

        alignmentStore.deleteDriftItemsByType(projectId, version, "ALIGNED");
        alignmentStore.deleteDriftItemsByType(projectId, version, "DOCUMENT_DRIFT");
        alignmentStore.deleteDriftItemsByType(projectId, version, "UNMAPPED");
        alignmentStore.deleteDriftItemsByType(projectId, version, "IMPLEMENTATION_REVIEW_REQUIRED");

        Map<String, String> paramValueByNormalizedSubject = new HashMap<>();
        for (ParameterClaim parameter : parameters) {
            paramValueByNormalizedSubject.putIfAbsent(
                    AlignmentNaming.normalize(parameter.parameter()), parameter.normalizedValue());
        }

        int drifts = 0;
        int aligned = 0;
        int unmapped = 0;
        int review = 0;
        for (KnowledgeClaimRecord requirement : requirements) {
            String conceptKey = "req:" + AlignmentNaming.keySegment(requirement.subject());
            BusinessConcept concept = alignmentStore.findConceptByKey(projectId, conceptKey).orElse(null);
            List<ConceptMember> codeMembers = concept == null ? List.of()
                    : alignmentStore.findMembers(projectId, concept.conceptId()).stream()
                    .filter(member -> "CODE".equals(member.sourceType())).toList();

            String configValue = paramValueByNormalizedSubject.get(AlignmentNaming.normalize(requirement.subject()));
            boolean configMismatch = configValue != null && requirement.objectValue() != null
                    && differs(requirement.objectValue(), configValue);

            String detail = configMismatch
                    ? "需求[" + requirement.subject() + "]=" + requirement.objectValue()
                    + "，配置=" + configValue
                    : "需求[" + requirement.subject() + "]";

            String conceptId = concept == null ? "UNKNOWN" : concept.conceptId();
            if (codeMembers.isEmpty()) {
                String driftId = driftId(projectId, version, requirement.claimId(), "UNMAPPED");
                alignmentStore.saveDriftItem(new DriftItem(
                        driftId, projectId, version, conceptId, conceptKey,
                        DriftType.UNMAPPED.name(), configMismatch ? "WARNING" : "INFO",
                        TruthRole.INTENT.name(), requirement.claimId(), null,
                        requirement.objectValue(), configValue,
                        detail + "：未映射到代码符号（不宣称已实现）",
                        "OPEN", null, Instant.now().toString()));
                unmapped++;
                drifts++;
            } else if (configMismatch) {
                String driftId = driftId(projectId, version, requirement.claimId(), "DOCUMENT_DRIFT");
                alignmentStore.saveDriftItem(new DriftItem(
                        driftId, projectId, version, conceptId, conceptKey,
                        DriftType.DOCUMENT_DRIFT.name(), "WARNING",
                        TruthRole.INTENT.name(), requirement.claimId(), null,
                        requirement.objectValue(), configValue,
                        detail + "：需求声明与配置不一致，创建文档更新候选；代码不自动覆盖需求",
                        "OPEN", null, Instant.now().toString()));
                drifts++;
            } else {
                String driftId = driftId(projectId, version, requirement.claimId(), "ALIGNED");
                alignmentStore.saveDriftItem(new DriftItem(
                        driftId, projectId, version, conceptId, conceptKey,
                        DriftType.ALIGNED.name(), "INFO",
                        TruthRole.INTENT.name(), requirement.claimId(), null,
                        requirement.objectValue(), null,
                        detail + "：已映射到 " + codeMembers.size() + " 个代码符号，未发现配置冲突",
                        "CLOSED", null, Instant.now().toString()));
                aligned++;
                drifts++;
            }
        }
        return new BuildResult(0, 0, 0, 0, drifts);
    }

    /** 查询漂移报告（含统计与清单）。 */
    public DriftReport report(String projectId, String version, String environment) {
        List<DriftItem> items = alignmentStore.findDriftItems(projectId, version, null);
        long aligned = items.stream().filter(item -> DriftType.ALIGNED.name().equals(item.driftType())).count();
        long documentDrift = items.stream()
                .filter(item -> DriftType.DOCUMENT_DRIFT.name().equals(item.driftType())).count();
        long unmapped = items.stream().filter(item -> DriftType.UNMAPPED.name().equals(item.driftType())).count();
        long review = items.stream()
                .filter(item -> DriftType.IMPLEMENTATION_REVIEW_REQUIRED.name().equals(item.driftType())).count();
        String commitSha = versionContextService.find(projectId, version, environment)
                .map(VersionContext::commitSha).orElse(null);
        return new DriftReport(projectId, version, commitSha,
                (int) aligned, (int) documentDrift, (int) unmapped, (int) review, items);
    }

    private boolean differs(String requirementValue, String configValue) {
        BigDecimal requirement = decimal(requirementValue);
        BigDecimal config = decimal(configValue);
        if (requirement != null && config != null) return requirement.compareTo(config) != 0;
        return !String.valueOf(requirementValue == null ? "" : requirementValue.trim())
                .equalsIgnoreCase(String.valueOf(configValue == null ? "" : configValue.trim()));
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.replace("%", "").replace("分钟", "").replace("min", "").replace(",", "").trim();
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String driftId(String projectId, String version, String sourceClaimId, String type) {
        return "di:" + sha256(projectId + "|" + version + "|" + sourceClaimId + "|" + type).substring(0, 32);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}