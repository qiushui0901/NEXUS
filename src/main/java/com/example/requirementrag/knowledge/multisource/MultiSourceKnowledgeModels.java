package com.example.requirementrag.knowledge.multisource;

import java.math.BigDecimal;
import java.util.List;

/**
 * 多源需求知识领域模型：数值表参数 Claim 与需求存疑 Claim。
 *
 * <p>这些模型是结构化知识的最小载体：保留来源位置、单位、边界与状态，
 * 避免把数值表和存疑压成无类型纯文本后混排。
 */
public final class MultiSourceKnowledgeModels {
    private MultiSourceKnowledgeModels() {
    }

    /** 查询意图：决定多源检索时应优先哪些来源。 */
    public enum KnowledgeQueryIntent {
        NORMATIVE,
        VALIDATION,
        PARAMETER,
        DOUBT,
        CONSISTENCY,
        IMPACT,
        GENERAL
    }

    /** 知识生命周期状态。 */
    public enum KnowledgeStatus {
        DRAFT,
        EXTRACTED,
        SUPPORTED,
        VERIFIED,
        PASSED,
        FAILED,
        OPEN,
        RESOLVED,
        REJECTED,
        CONFLICTED,
        STALE,
        OBSOLETE
    }

    /** 数值表参数值类型。 */
    public enum ParameterValueType {
        INTEGER,
        DECIMAL,
        PERCENTAGE,
        DURATION,
        COUNT,
        BOOLEAN,
        ENUM,
        TEXT
    }

    /** 需求存疑状态：OPEN 不能作为确认事实进入普通规范检索。 */
    public enum DoubtStatus {
        OPEN,
        UNDER_DISCUSSION,
        RESOLVED,
        REJECTED,
        OBSOLETE
    }

    /** 数值表参数 Claim：保留 Workbook/Sheet/行列、单位、范围、精度与边界。 */
    public record ParameterClaim(
            String claimId,
            String projectId,
            String version,
            String workbook,
            String sheetName,
            int rowNumber,
            String columnRange,
            String module,
            String parameter,
            String rawValue,
            String normalizedValue,
            String unit,
            BigDecimal minValue,
            BigDecimal maxValue,
            int precision,
            boolean inclusiveBoundary,
            ParameterValueType valueType,
            String factKey,
            String evidenceLocation
    ) {
        public ParameterClaim {
            if (claimId == null || claimId.isBlank()) throw new IllegalArgumentException("claimId 不能为空");
            if (parameter == null || parameter.isBlank()) throw new IllegalArgumentException("parameter 不能为空");
        }
    }

    /** 需求存疑 Claim：未决问题与风险，状态由人工维护。 */
    public record DoubtClaim(
            String doubtId,
            String projectId,
            String version,
            String module,
            String question,
            String answer,
            String sourceSheet,
            int rowNumber,
            DoubtStatus status,
            String owner,
            String severity,
            String dueDate,
            List<String> proposedOptions,
            String evidenceLocation
    ) {
        public DoubtClaim {
            proposedOptions = proposedOptions == null ? List.of() : List.copyOf(proposedOptions);
            status = status == null ? DoubtStatus.OPEN : status;
            if (doubtId == null || doubtId.isBlank()) throw new IllegalArgumentException("doubtId 不能为空");
            if (question == null || question.isBlank()) throw new IllegalArgumentException("question 不能为空");
        }
    }
}