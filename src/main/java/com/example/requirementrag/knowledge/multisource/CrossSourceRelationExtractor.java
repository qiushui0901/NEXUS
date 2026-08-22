package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.CrossSourceRelation;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.CrossSourceRelationType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 跨来源关系抽取器（Phase 3/4 核心）：
 * 基于结构化来源生成 TEST_CASE->VERIFIES、PARAMETER_TABLE->SUPPORTS、DOUBT->RAISES_DOUBT 等关系。
 *
 * <p>首期为确定性规则；后续可加入 LLM 提供者，但每条关系必须绑定双方 Claim、来源类型、Evidence 和版本。
 */
@Component
public class CrossSourceRelationExtractor {

    /** 从统一 Claim 与存疑列表生成跨来源关系。 */
    public List<CrossSourceRelation> extract(List<UnifiedKnowledgeClaim> candidates, List<DoubtClaim> doubts) {
        List<CrossSourceRelation> result = new ArrayList<>();
        List<UnifiedKnowledgeClaim> requirements = candidates.stream()
                .filter(claim -> claim.sourceType() == SourceType.REQUIREMENT).toList();

        for (UnifiedKnowledgeClaim testCase : candidates) {
            if (testCase.sourceType() != SourceType.TEST_CASE) continue;
            String target = targetRequirementId(testCase, requirements);
            if (target != null) {
                result.add(new CrossSourceRelation(
                        relationId("verifies", testCase.claimId(), target),
                        testCase.projectId(), testCase.version(), testCase.claimId(), target,
                        CrossSourceRelationType.VERIFIES, testCase.evidenceLocation(),
                        "coveredRequirementId=" + testCase.factKey()));
            }
        }

        for (UnifiedKnowledgeClaim parameter : candidates) {
            if (parameter.sourceType() != SourceType.PARAMETER_TABLE) continue;
            UnifiedKnowledgeClaim requirement = findRequirement(requirements, parameter.subject(), parameter.predicate());
            if (requirement != null) {
                result.add(new CrossSourceRelation(
                        relationId("supports", parameter.claimId(), requirement.claimId()),
                        parameter.projectId(), parameter.version(), parameter.claimId(), requirement.claimId(),
                        CrossSourceRelationType.SUPPORTS, parameter.evidenceLocation(),
                        "subject=" + parameter.subject() + ",predicate=" + parameter.predicate()));
            }
        }

        for (DoubtClaim doubt : doubts == null ? List.<DoubtClaim>of() : doubts) {
            UnifiedKnowledgeClaim requirement = findRequirement(requirements, safe(doubt.module()), null);
            String target = requirement != null ? requirement.claimId() : safe(doubt.module());
            result.add(new CrossSourceRelation(
                    relationId("doubts", doubt.doubtId(), target),
                    doubt.projectId(), doubt.version(), doubt.doubtId(), target,
                    CrossSourceRelationType.RAISES_DOUBT, doubt.evidenceLocation(),
                    "module=" + safe(doubt.module())));
        }
        return List.copyOf(result);
    }

    private String targetRequirementId(UnifiedKnowledgeClaim testCase, List<UnifiedKnowledgeClaim> requirements) {
        // factKey 已包含 coveredRequirementId 或 module|title；优先匹配同 factKey 的 REQUIREMENT Claim。
        return requirements.stream()
                .filter(requirement -> requirement.factKey().equalsIgnoreCase(testCase.factKey()))
                .map(UnifiedKnowledgeClaim::claimId)
                .findFirst()
                .orElse("req:" + testCase.factKey());
    }

    private UnifiedKnowledgeClaim findRequirement(List<UnifiedKnowledgeClaim> requirements, String subject, String predicate) {
        for (UnifiedKnowledgeClaim requirement : requirements) {
            if (subject != null && !subject.isBlank()
                    && requirement.subject() != null && requirement.subject().equalsIgnoreCase(subject)) {
                if (predicate == null || predicate.isBlank()
                        || (requirement.predicate() != null && requirement.predicate().equalsIgnoreCase(predicate))) {
                    return requirement;
                }
            }
        }
        return null;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String relationId(String prefix, String source, String target) {
        return "rel:" + prefix + ":" + sha256(source + "|" + target).substring(0, 32);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}