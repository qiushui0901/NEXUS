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

    /** 跨来源关系抽取结果：真实关系 + 未解析原因（不生成悬空 target）。 */
    public record CrossSourceExtraction(List<CrossSourceRelation> relations, List<String> unresolved) {
        public CrossSourceExtraction {
            relations = relations == null ? List.of() : List.copyOf(relations);
            unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
        }
    }

    /** 从统一 Claim 与存疑列表生成跨来源关系；找不到真实目标时记录 unresolved 而不是伪造 ID。 */
    public CrossSourceExtraction extract(List<UnifiedKnowledgeClaim> candidates, List<DoubtClaim> doubts) {
        List<CrossSourceRelation> result = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        List<UnifiedKnowledgeClaim> requirements = candidates.stream()
                .filter(claim -> claim.sourceType() == SourceType.REQUIREMENT).toList();

        for (UnifiedKnowledgeClaim testCase : candidates) {
            if (testCase.sourceType() != SourceType.TEST_CASE) continue;
            UnifiedKnowledgeClaim target = findRequirement(requirements, testCase.factKey());
            if (target != null) {
                result.add(new CrossSourceRelation(
                        relationId("verifies", testCase.claimId(), target.claimId()),
                        testCase.projectId(), testCase.version(), testCase.claimId(), target.claimId(),
                        CrossSourceRelationType.VERIFIES, testCase.evidenceLocation(),
                        "coveredRequirementId=" + testCase.factKey()));
            } else {
                unresolved.add("TEST_CASE " + testCase.claimId() + " 未匹配到需求 Claim");
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
            } else {
                unresolved.add("PARAMETER_TABLE " + parameter.claimId() + " 未匹配到需求 Claim");
            }
        }

        for (DoubtClaim doubt : doubts == null ? List.<DoubtClaim>of() : doubts) {
            UnifiedKnowledgeClaim requirement = findRequirement(requirements, safe(doubt.module()), null);
            if (requirement != null) {
                result.add(new CrossSourceRelation(
                        relationId("doubts", doubt.doubtId(), requirement.claimId()),
                        doubt.projectId(), doubt.version(), doubt.doubtId(), requirement.claimId(),
                        CrossSourceRelationType.RAISES_DOUBT, doubt.evidenceLocation(),
                        "module=" + safe(doubt.module())));
            } else {
                unresolved.add("DOUBT " + doubt.doubtId() + " 未匹配到需求 Claim");
            }
        }
        return new CrossSourceExtraction(List.copyOf(result), List.copyOf(unresolved));
    }

    private UnifiedKnowledgeClaim findRequirement(List<UnifiedKnowledgeClaim> requirements, String factKey) {
        return requirements.stream()
                .filter(requirement -> requirement.factKey().equalsIgnoreCase(factKey))
                .findFirst().orElse(null);
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