package com.example.requirementrag.conflict;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 面向版本范围、证据背书的知识冲突分析的类型化契约。 */
public final class KnowledgeConflictModels {
    private KnowledgeConflictModels() {
    }

    /** 声明来源类型：需求、测试、数值表、存疑、代码或 Wiki。旧 TEST 兼容映射为 TEST_CASE。 */
    public enum SourceType {
        REQUIREMENT,
        TEST_CASE,
        TEST_RESULT,
        PARAMETER_TABLE,
        DOUBT,
        CODE,
        WIKI,
        /** 需求文档 LLM 语义标注候选：未经人工审核，只能作为候选召回，不能作为确认事实。 */
        REQUIREMENT_SEMANTIC,
        /** Claim 向量投影检索：SQLite 权威 + Qdrant 可弃语义块投影，命中后逐条水化 Claim。 */
        CLAIM_VECTOR,
        /** @deprecated 语义不明确，兼容读取时映射为 TEST_CASE；新数据禁止继续使用。 */
        @Deprecated
        TEST;

        /** 兼容归一化：旧 TEST 映射为 TEST_CASE，未知值返回 null。 */
        public static SourceType normalize(String value) {
            if (value == null || value.isBlank()) return null;
            String normalized = value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
            if ("TEST".equals(normalized)) return TEST_CASE;
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        /** 返回规范化后的来源类型：旧 TEST 映射为 TEST_CASE，其余原样返回。 */
        public SourceType normalized() {
            return this == TEST ? TEST_CASE : this;
        }
    }

    /** 声明权威级别：PRIMARY 为原始证据，SECONDARY 为验证/实现证据，DERIVED 为派生知识。 */
    public enum Authority {
        PRIMARY,
        SECONDARY,
        DERIVED
    }

    /** 冲突类型，按来源组合与证据污染情况细分。 */
    public enum ConflictType {
        /** 需求期望与代码实现结论不一致。 */
        REQUIREMENT_CODE,
        /** 需求期望与测试证据结论不一致。 */
        REQUIREMENT_TEST,
        /** 代码实现与测试证据结论不一致。 */
        CODE_TEST,
        /** 派生 Wiki 与原始证据结论不一致。 */
        WIKI_PRIMARY,
        /** 同一来源内部对同一事实结论不一致。 */
        SOURCE_INTERNAL,
        /** 证据属于其他版本，已阻止作为目标版本事实使用。 */
        VERSION_CONTAMINATION,
        /** 证据属于其他项目，已阻止作为当前项目事实使用。 */
        PROJECT_CONTAMINATION,
        /** 派生 Wiki 声明缺少原始证据支撑。 */
        WIKI_MISSING_PRIMARY_EVIDENCE
    }

    /** 冲突严重级别，BLOCKING 表示必须人工处理。 */
    public enum Severity {
        INFO,
        WARNING,
        ERROR,
        BLOCKING
    }

    /** 冲突解决状态，当前仅支持 OPEN（待解决）。 */
    public enum ResolutionStatus {
        OPEN
    }

    /** 报告总体状态：无冲突、需复核或被阻止。 */
    public enum ReportStatus {
        CLEAR,
        REVIEW_REQUIRED,
        BLOCKED
    }

    /** 指向原始或派生来源材料的有界指针。 */
    public record KnowledgeEvidence(
            @NotBlank String evidenceId,
            String title,
            String source,
            String location,
            String excerpt
    ) {
    }

    /** 结构化事实；语义等价性通过稳定的 factKey 声明，不做猜测。 */
    public record KnowledgeClaim(
            String claimId,
            @NotBlank String projectId,
            @NotBlank String version,
            @NotBlank String factKey,
            @NotBlank String value,
            @NotNull SourceType sourceType,
            Authority authority,
            @Valid @NotNull KnowledgeEvidence evidence,
            List<String> supportingEvidenceIds
    ) {
        public KnowledgeClaim {
            supportingEvidenceIds = supportingEvidenceIds == null ? List.of() : List.copyOf(supportingEvidenceIds);
        }
    }

    /** 冲突分析请求：目标项目、目标版本与一批待分析声明。 */
    public record AnalyzeRequest(
            @NotBlank String projectId,
            @NotBlank String targetVersion,
            @NotNull List<@Valid KnowledgeClaim> claims
    ) {
        public AnalyzeRequest {
            claims = claims == null ? List.of() : List.copyOf(claims);
        }
    }

    /** 一条已识别出的知识冲突及其涉及的声明。 */
    public record KnowledgeConflict(
            String conflictId,
            ConflictType type,
            Severity severity,
            ResolutionStatus resolutionStatus,
            String factKey,
            String message,
            List<KnowledgeClaim> claims
    ) {
        public KnowledgeConflict {
            claims = claims == null ? List.of() : List.copyOf(claims);
        }
    }

    /** 冲突分析报告：总体状态、声明与冲突统计、冲突列表和警告。 */
    public record KnowledgeConflictReport(
            String projectId,
            String targetVersion,
            ReportStatus status,
            int claimCount,
            int conflictCount,
            List<KnowledgeConflict> conflicts,
            List<String> warnings
    ) {
        public KnowledgeConflictReport {
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** 构造无冲突的空报告（CLEAR 状态）。 */
        public static KnowledgeConflictReport empty(String projectId, String targetVersion) {
            return new KnowledgeConflictReport(projectId, targetVersion, ReportStatus.CLEAR,
                    0, 0, List.of(), List.of());
        }
    }
}
