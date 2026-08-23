package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.List;

/**
 * 多源需求知识领域模型：数值表参数 Claim、需求存疑 Claim、测试用例/结果 Claim 与统一 Claim。
 *
 * <p>这些模型是结构化知识的最小载体：保留来源位置、单位、边界、状态与来源类型，
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
            String evidenceLocation,
            KnowledgeStatus status
    ) {
        public ParameterClaim {
            if (claimId == null || claimId.isBlank()) throw new IllegalArgumentException("claimId 不能为空");
            if (parameter == null || parameter.isBlank()) throw new IllegalArgumentException("parameter 不能为空");
            status = status == null ? KnowledgeStatus.SUPPORTED : status;
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

    /** 测试用例 Claim：验证定义，不等同于需求规范。 */
    public record TestCaseClaim(
            String claimId,
            String projectId,
            String version,
            String testCaseId,
            String title,
            String module,
            String preconditions,
            String steps,
            String expectedResult,
            String coveredRequirementId,
            String framework,
            String filePath,
            String testMethod,
            String evidenceLocation,
            KnowledgeStatus status
    ) {
        public TestCaseClaim {
            if (claimId == null || claimId.isBlank()) throw new IllegalArgumentException("claimId 不能为空");
            if (testCaseId == null || testCaseId.isBlank()) throw new IllegalArgumentException("testCaseId 不能为空");
            status = status == null ? KnowledgeStatus.SUPPORTED : status;
        }
    }

    /** 测试结果 Claim：实际执行证据，不覆盖需求规范。 */
    public record TestResultClaim(
            String claimId,
            String projectId,
            String version,
            String testRunId,
            String testCaseId,
            String executionStatus,
            String executedAt,
            String environment,
            String actualResult,
            String failureMessage,
            String evidenceLocation,
            KnowledgeStatus status
    ) {
        public TestResultClaim {
            if (claimId == null || claimId.isBlank()) throw new IllegalArgumentException("claimId 不能为空");
            if (testCaseId == null || testCaseId.isBlank()) throw new IllegalArgumentException("testCaseId 不能为空");
            status = status == null ? KnowledgeStatus.SUPPORTED : status;
        }
    }

    /** 统一 Claim：跨来源的规范化事实视图，用于同 factKey 聚合与冲突分析。 */
    public record UnifiedKnowledgeClaim(
            String claimId,
            String projectId,
            String version,
            String factKey,
            String subject,
            String predicate,
            String value,
            String valueType,
            String unit,
            SourceType sourceType,
            Authority authority,
            KnowledgeStatus status,
            String effectiveFrom,
            String effectiveTo,
            String evidenceLocation,
            String module
    ) {
    }

    /** 多源结论状态。 */
    public enum AnswerStatus {
        CONFIRMED,
        SUPPORTED,
        PARTIALLY_SUPPORTED,
        REVIEW_REQUIRED,
        CONFLICTED,
        NO_EVIDENCE,
        NO_RESULT
    }

    /** 多源冲突类型（Phase 3 新增）。 */
    public enum MultiSourceConflictType {
        REQUIREMENT_PARAMETER,
        PARAMETER_TEST,
        TEST_RESULT_EXPECTATION,
        REQUIREMENT_DOUBT,
        VERSION_INTERNAL,
        SOURCE_STALE,
        MISSING_VALIDATION
    }

    /** 跨来源关系类型：测试验证需求、参数支撑需求、存疑指向需求、需求由代码实现等。 */
    public enum CrossSourceRelationType {
        VERIFIES,
        SUPPORTS,
        RAISES_DOUBT,
        IMPLEMENTED_BY,
        COVERS
    }

    /** 跨来源关系：绑定 source/target Claim、来源类型、Evidence 与版本。 */
    public record CrossSourceRelation(
            String relationId,
            String projectId,
            String version,
            String sourceClaimId,
            String targetClaimId,
            CrossSourceRelationType type,
            String evidenceLocation,
            String metadata
    ) {
        public CrossSourceRelation {
            if (relationId == null || relationId.isBlank()) throw new IllegalArgumentException("relationId 不能为空");
        }
    }

    /** 多源检索请求：HTTP API 入参，意图可空（自动判定）。 */
    public record MultiSourceSearchRequest(
            @NotBlank String projectId,
            @NotBlank String version,
            @NotBlank String query,
            KnowledgeQueryIntent intent,
            Integer limit,
            Integer page
    ) {
    }

    /** 多源检索响应：统一 Claim、Evidence、冲突、存疑与解释，附带分页元数据。 */
    public record MultiSourceSearchResponse(
            String query,
            KnowledgeQueryIntent intent,
            AnswerStatus answerStatus,
            List<UnifiedKnowledgeClaim> claims,
            List<String> evidence,
            List<String> conflicts,
            List<DoubtClaim> doubts,
            List<String> explanations,
            List<String> warnings,
            List<CrossSourceRelation> relations,
            int total,
            int page,
            int limit,
            boolean hasMore
    ) {
        public MultiSourceSearchResponse {
            claims = claims == null ? List.of() : List.copyOf(claims);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
            doubts = doubts == null ? List.of() : List.copyOf(doubts);
            explanations = explanations == null ? List.of() : List.copyOf(explanations);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            relations = relations == null ? List.of() : List.copyOf(relations);
        }
    }
}