package com.example.requirementrag.knowledge.multisource.alignment;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BusinessConcept;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.ConceptMember;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DoubtImpact;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DoubtImpactBuildResult;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.VersionContext;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 存疑影响分析（Phase 5）：OPEN 存疑自动补全受影响的代码/参数/测试，
 * 解决存疑时绑定人工结论与 Resolution Evidence，并关闭对应影响项。
 *
 * <p>存疑本身不进入确认事实；影响范围绑定 VersionContext，可审计、可关闭。
 */
@Service
public class DoubtImpactService {

    private static final Set<String> IMPACT_TARGET_TYPES =
            Set.of("CODE", "PARAMETER_TABLE", "TEST_CASE");

    private final MultiSourceKnowledgeStore knowledgeStore;
    private final CodeCentricAlignmentStore alignmentStore;
    private final BusinessConceptService businessConceptService;
    private final VersionContextService versionContextService;

    public DoubtImpactService(MultiSourceKnowledgeStore knowledgeStore,
                              CodeCentricAlignmentStore alignmentStore,
                              BusinessConceptService businessConceptService,
                              VersionContextService versionContextService) {
        this.knowledgeStore = knowledgeStore;
        this.alignmentStore = alignmentStore;
        this.businessConceptService = businessConceptService;
        this.versionContextService = versionContextService;
    }

    /** 构建存疑影响（幂等：先重建概念层，再按 VersionContext 重建影响项）。 */
    public DoubtImpactBuildResult build(String projectId, String version, String environment) {
        VersionContext context = versionContextService.resolve(projectId, version, environment);
        businessConceptService.build(projectId, version);
        alignmentStore.deleteDoubtImpactsByVersion(projectId, version, context.contextId());

        List<DoubtClaim> doubts = knowledgeStore.findDoubts(projectId, version);
        List<BusinessConcept> concepts = alignmentStore.findConcepts(projectId);
        Map<String, List<ConceptMember>> membersByConcept = new HashMap<>();
        for (BusinessConcept concept : concepts) {
            membersByConcept.put(concept.conceptId(),
                    alignmentStore.findMembers(projectId, concept.conceptId(), version));
        }

        int totalImpacts = 0;
        int affectedDoubts = 0;
        List<DoubtImpact> impactBatch = new ArrayList<>();
        for (DoubtClaim doubt : doubts) {
            int before = totalImpacts;
            for (BusinessConcept concept : matchConcepts(concepts, doubt)) {
                List<ConceptMember> members = membersByConcept.getOrDefault(concept.conceptId(), List.of());
                for (ConceptMember member : members) {
                    if (!IMPACT_TARGET_TYPES.contains(member.sourceType())) continue;
                    String targetClaimId = member.claimId();
                    String targetExternalId = member.externalId();
                    String targetName = member.displayName();
                    String impactId = impactId(projectId, version, context.contextId(), doubt.doubtId(),
                            member.sourceType(), targetClaimId, targetExternalId);
                    impactBatch.add(new DoubtImpact(
                            impactId, projectId, version, context.contextId(),
                            doubt.doubtId(), doubt.question(), concept.conceptId(), concept.canonicalKey(),
                            member.sourceType(), targetClaimId, targetExternalId, targetName,
                            doubt.severity(), doubt.owner(), doubt.dueDate(),
                            "OPEN", null, null, Instant.now().toString(), null));
                    totalImpacts++;
                }
            }
            if (totalImpacts > before) {
                affectedDoubts++;
            }
        }
        alignmentStore.saveDoubtImpacts(impactBatch);
        return new DoubtImpactBuildResult(totalImpacts, affectedDoubts);
    }

    /** 查询指定环境下的存疑影响。 */
    public List<DoubtImpact> impacts(String projectId, String version, String environment, String status) {
        VersionContext context = versionContextService.resolve(projectId, version, environment);
        return alignmentStore.findDoubtImpacts(projectId, version, context.contextId(), status);
    }

    /** 人工关闭存疑：更新 Doubt 表状态，绑定 Resolution Evidence，并关闭对应影响项。 */
    public List<DoubtImpact> resolve(String projectId, String version, String environment,
                                     String doubtId, String conclusion, String resolutionEvidenceId) {
        VersionContext context = versionContextService.resolve(projectId, version, environment);
        knowledgeStore.updateDoubtResolution(doubtId, conclusion, "RESOLVED");
        alignmentStore.resolveDoubtImpacts(projectId, version, context.contextId(), doubtId,
                conclusion, resolutionEvidenceId);
        return alignmentStore.findDoubtImpactsByDoubt(projectId, version, context.contextId(), doubtId);
    }

    private List<BusinessConcept> matchConcepts(List<BusinessConcept> concepts, DoubtClaim doubt) {
        String module = AlignmentNaming.normalize(doubt.module());
        List<BusinessConcept> result = new ArrayList<>();
        for (BusinessConcept concept : concepts) {
            if (result.size() >= 10) break;
            String conceptModule = AlignmentNaming.normalize(concept.module());
            String canonical = AlignmentNaming.normalize(concept.canonicalKey());
            if (!module.isBlank() && (module.equals(conceptModule) || canonical.contains(module))) {
                result.add(concept);
            } else if (AlignmentNaming.namesRelated(concept.displayName(), doubt.question())) {
                result.add(concept);
            }
        }
        return result;
    }

    private String impactId(String projectId, String version, String contextId, String doubtId,
                            String targetType, String targetClaimId, String targetExternalId) {
        return "imp:" + sha256(projectId + "|" + version + "|" + contextId + "|" + doubtId
                + "|" + targetType + "|" + safe(targetClaimId) + "|" + safe(targetExternalId)).substring(0, 24);
    }

    private String safe(String value) {
        return value == null ? "" : value;
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