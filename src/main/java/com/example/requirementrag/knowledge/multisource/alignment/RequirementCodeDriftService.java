package com.example.requirementrag.knowledge.multisource.alignment;

import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.AlignmentRelation;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 需求—代码漂移检测（Phase 4）：把需求从默认裁决者调整为可审计的意图/漂移来源。
 *
 * <p>每个需求声明映射到业务概念，全部结论绑定 VersionContext：
 * 无代码成员 → UNMAPPED；需求值与配置值不一致 → DOCUMENT_DRIFT；
 * 存在确定性代码关系（READS_CONFIG / IMPLEMENTED_BY / ALIGNED_WITH）且无冲突 → ALIGNED；
 * 仅名称映射而无实现证据 → MAPPED_NO_IMPLEMENTATION_ASSERTION（不宣称已实现）。
 */
@Service
public class RequirementCodeDriftService {

    /** 只有这些确定性关系才算“实现已验证”，避免同名符号被误判为已对齐。 */
    private static final Set<String> IMPLEMENTATION_EVIDENCE_TYPES =
            Set.of("READS_CONFIG", "IMPLEMENTED_BY", "ALIGNED_WITH");

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

    /** 构建项目/版本的需求—代码漂移报告（按 VersionContext 隔离，幂等重建 Phase 4 结论）。 */
    public BuildResult build(String projectId, String version, String environment) {
        VersionContext context = versionContextService.resolve(projectId, version, environment);
        List<KnowledgeClaimRecord> requirements = knowledgeStore.findClaimsByProjectVersion(projectId, version)
                .stream().filter(claim -> claim.sourceType() == SourceType.REQUIREMENT).toList();
        List<ParameterClaim> parameters = knowledgeStore.findParameters(projectId, version);

        for (String type : List.of("ALIGNED", "DOCUMENT_DRIFT", "UNMAPPED",
                "MAPPED_NO_IMPLEMENTATION_ASSERTION", "IMPLEMENTATION_REVIEW_REQUIRED")) {
            alignmentStore.deleteDriftItemsByType(projectId, version, context.contextId(), type);
        }

        Map<String, String> paramValueByNormalizedSubject = new HashMap<>();
        for (ParameterClaim parameter : parameters) {
            paramValueByNormalizedSubject.putIfAbsent(
                    AlignmentNaming.normalize(parameter.parameter()), parameter.normalizedValue());
        }

        int drifts = 0;
        for (KnowledgeClaimRecord requirement : requirements) {
            String conceptKey = "req:" + AlignmentNaming.keySegment(requirement.subject());
            BusinessConcept concept = alignmentStore.findConceptByKey(projectId, conceptKey).orElse(null);
            List<ConceptMember> codeMembers = concept == null ? List.of()
                    : alignmentStore.findMembers(projectId, concept.conceptId(), version).stream()
                    .filter(member -> "CODE".equals(member.sourceType())).toList();

            String configValue = paramValueByNormalizedSubject.get(AlignmentNaming.normalize(requirement.subject()));
            boolean configMismatch = configValue != null && requirement.objectValue() != null
                    && differs(requirement.objectValue(), configValue);

            String detail = configMismatch
                    ? "需求[" + requirement.subject() + "]=" + requirement.objectValue()
                    + "，配置=" + configValue
                    : "需求[" + requirement.subject() + "]";

            String conceptId = concept == null ? "UNKNOWN" : concept.conceptId();
            boolean hasCodeEvidence = hasImplementationEvidence(projectId, version, context.contextId(), codeMembers);

            if (codeMembers.isEmpty()) {
                save(projectId, version, context.contextId(), requirement, conceptId, conceptKey,
                        DriftType.UNMAPPED.name(), configMismatch ? "WARNING" : "INFO",
                        requirement.objectValue(), configValue,
                        detail + "：未映射到代码符号（不宣称已实现）", "OPEN");
                drifts++;
            } else if (configMismatch) {
                save(projectId, version, context.contextId(), requirement, conceptId, conceptKey,
                        DriftType.DOCUMENT_DRIFT.name(), "WARNING",
                        requirement.objectValue(), configValue,
                        detail + "：需求声明与配置不一致，创建文档更新候选；代码不自动覆盖需求", "OPEN");
                drifts++;
            } else if (hasCodeEvidence) {
                save(projectId, version, context.contextId(), requirement, conceptId, conceptKey,
                        DriftType.ALIGNED.name(), "INFO",
                        requirement.objectValue(), null,
                        detail + "：已映射到 " + codeMembers.size() + " 个代码符号，且存在确定性实现关系，未发现配置冲突",
                        "CLOSED");
                drifts++;
            } else {
                save(projectId, version, context.contextId(), requirement, conceptId, conceptKey,
                        DriftType.MAPPED_NO_IMPLEMENTATION_ASSERTION.name(), "INFO",
                        requirement.objectValue(), null,
                        detail + "：名称映射到 " + codeMembers.size() + " 个代码符号，但缺少确定性实现证据（不宣称已实现）",
                        "OPEN");
                drifts++;
            }
        }
        return new BuildResult(0, 0, 0, 0, drifts);
    }

    /** 查询指定环境下的漂移报告（含统计与清单）。 */
    public DriftReport report(String projectId, String version, String environment) {
        VersionContext context = versionContextService.resolve(projectId, version, environment);
        List<DriftItem> items = alignmentStore.findDriftItems(projectId, version, context.contextId(), null);
        long aligned = items.stream().filter(item -> DriftType.ALIGNED.name().equals(item.driftType())).count();
        long documentDrift = items.stream()
                .filter(item -> DriftType.DOCUMENT_DRIFT.name().equals(item.driftType())).count();
        long unmapped = items.stream().filter(item -> DriftType.UNMAPPED.name().equals(item.driftType())).count();
        long mappedNoAssertion = items.stream()
                .filter(item -> DriftType.MAPPED_NO_IMPLEMENTATION_ASSERTION.name().equals(item.driftType())).count();
        long review = items.stream()
                .filter(item -> DriftType.IMPLEMENTATION_REVIEW_REQUIRED.name().equals(item.driftType())).count();
        return new DriftReport(projectId, version, context.commitSha(),
                (int) aligned, (int) documentDrift, (int) unmapped, (int) mappedNoAssertion,
                (int) review, items);
    }

    /** 是否存在确定性实现关系：代码成员作为关系目标且类型属于实现证据。 */
    private boolean hasImplementationEvidence(String projectId, String version, String contextId,
                                              List<ConceptMember> codeMembers) {
        for (ConceptMember member : codeMembers) {
            if (member.externalId() == null) continue;
            for (AlignmentRelation relation : alignmentStore.findAlignmentRelationsForExternal(
                    projectId, version, contextId, member.externalId())) {
                if (IMPLEMENTATION_EVIDENCE_TYPES.contains(relation.relationType())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void save(String projectId, String version, String contextId, KnowledgeClaimRecord requirement,
                      String conceptId, String conceptKey, String driftType, String severity,
                      String sourceValue, String targetValue, String detail, String status) {
        String driftId = driftId(projectId, version, contextId, requirement.claimId(), driftType);
        alignmentStore.saveDriftItem(new DriftItem(
                driftId, projectId, version, contextId, conceptId, conceptKey, driftType, severity,
                TruthRole.INTENT.name(), requirement.claimId(), null, sourceValue, targetValue,
                detail, status, null, Instant.now().toString()));
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

    private String driftId(String projectId, String version, String contextId, String sourceClaimId, String type) {
        return "di:" + sha256(projectId + "|" + version + "|" + contextId + "|" + sourceClaimId
                + "|" + type).substring(0, 32);
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
