package com.example.requirementrag.requirement.semantic;

import java.time.Instant;
import java.util.List;

/**
 * 需求语义 Chunk 增强契约（0.9.5）。
 *
 * <p>分层原则：原文 Chunk 是证据，LLM 语义标注只是面向召回的结构化索引候选；
 * 未审核的语义结果默认 {@code CANDIDATE}，不能直接作为规范事实。</p>
 */
public final class RequirementSemanticModels {
    private RequirementSemanticModels() {
    }

    /** 语义字段确定性分层：原文明确表达 &gt; 同块推导 &gt; 模型推断 &gt; 上下文缺失。 */
    public enum SemanticCertainty { EXPLICIT, DERIVED, INFERRED, UNKNOWN }

    /** 单条标注的抽取状态：成功 / 失败（含错误码）/ 预算或开关原因跳过。 */
    public enum ExtractionStatus { SUCCEEDED, FAILED, SKIPPED }

    /** 候选事实治理状态：语义标注默认 CANDIDATE，升级 VERIFIED 需人工审核。 */
    public enum ClaimStatus { CANDIDATE, VERIFIED, REJECTED, CONFLICTED, STALE }

    /** 稳定错误码，公开层统一加 {@code SEMANTIC_} 前缀。 */
    public enum SemanticErrorCode {
        JSON_PARSE_FAILED, SCHEMA_INVALID, EVIDENCE_UNAVAILABLE, NUMERIC_INVALID,
        FACT_KEY_INVALID, MODEL_TIMEOUT, MODEL_RATE_LIMITED, MODEL_UNAVAILABLE, BUDGET_EXCEEDED
    }

    /** 条件与数值允许的比较操作符（受控枚举，禁止自由文本）。 */
    public enum SemanticOperator {
        EQ, NE, GT, GTE, LT, LTE, IN, NOT_IN, BETWEEN, BEFORE, AFTER, REQUIRES, FORBIDS, UNKNOWN
    }

    /** 条件值类型（受控枚举）。 */
    public enum SemanticValueType { NUMBER, STRING, BOOLEAN, ENUM, DATE, DURATION, RANGE, UNKNOWN }

    /** 用户问题扩展类型（受控枚举）。 */
    public enum SemanticQuestionType {
        WHO, CONDITION, EVENT, VALUE, UNIT, IMPACT, IMPLEMENTATION, CONFLICT, DOUBT
    }

    // ---------------- LLM JSON 契约（字段名与 Prompt 输出结构严格一致） ----------------
    // 枚举字段在 JSON 绑定层保持 String，由 Validator 归一化并校验受控词表，
    // 避免未知枚举值被误判为 JSON 解析失败。

    public record SemanticEntity(
            String name,
            String type,
            List<String> aliases,
            String certainty,
            String evidenceQuote
    ) {
        public SemanticEntity {
            aliases = immutable(aliases);
        }
    }

    public record SemanticCondition(
            String subject,
            String field,
            String operator,
            String value,
            String unit,
            String valueType,
            String logicalGroup,
            String certainty,
            String evidenceQuote
    ) {
    }

    public record SemanticEvent(
            String subject,
            String event,
            String object,
            String result,
            String condition,
            String certainty,
            String evidenceQuote
    ) {
    }

    public record SemanticNumericFact(
            String subject,
            String field,
            String value,
            Double normalizedValue,
            String unit,
            String normalizedUnit,
            String operator,
            String certainty,
            String evidenceQuote
    ) {
    }

    public record SemanticClaimCandidate(
            String factKey,
            String subject,
            String predicate,
            String value,
            String unit,
            String certainty,
            String evidenceQuote
    ) {
    }

    public record SemanticQuestion(
            String text,
            String type
    ) {
    }

    public record SemanticAnnotationResult(
            List<SemanticEntity> entities,
            List<SemanticCondition> conditions,
            List<SemanticEvent> events,
            List<SemanticNumericFact> numericFacts,
            List<SemanticClaimCandidate> claims,
            List<SemanticQuestion> questionExpansions,
            List<String> uncertainties,
            List<String> missingContext,
            boolean selfContained
    ) {
        public SemanticAnnotationResult {
            entities = immutable(entities);
            conditions = immutable(conditions);
            events = immutable(events);
            numericFacts = immutable(numericFacts);
            claims = immutable(claims);
            questionExpansions = immutable(questionExpansions);
            uncertainties = immutable(uncertainties);
            missingContext = immutable(missingContext);
        }

        /** 空结果：模型判断该 Chunk 没有任何可抽取事实，不视为失败。 */
        public static SemanticAnnotationResult empty() {
            return new SemanticAnnotationResult(List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), true);
        }
    }

    // ---------------- 构建输入 / 输出 ----------------

    /** 单次语义标注输入：一个父块或一个抽取窗口，rawText 是唯一事实来源。
     *  windowIndex/startOffset/endOffset 是窗口在父块内的确定性坐标（endOffset 为开区间），
     *  审核与跨窗口拼接依赖它们，windowId 只是哈希无法还原顺序。 */
    public record SemanticAnnotationInput(
            String projectId,
            String documentId,
            String requirementVersion,
            String sourceChunkId,
            String parentId,
            String windowId,
            int windowIndex,
            int startOffset,
            int endOffset,
            String sourceFile,
            int parentOrder,
            String sectionPath,
            String heading,
            String rawText,
            String contentHash
    ) {
    }

    /** 一次 annotate 调用的结果：成功携带标注，失败携带稳定错误码。 */
    public record SemanticAnnotationOutcome(
            SemanticAnnotationResult annotation,
            SemanticErrorCode errorCode,
            int attempts,
            int modelCalls,
            long latencyMs,
            int tokenEstimate
    ) {
        public boolean succeeded() {
            return errorCode == null && annotation != null;
        }

        public static SemanticAnnotationOutcome failure(SemanticErrorCode errorCode, int attempts,
                                                        int modelCalls, long latencyMs, int tokenEstimate) {
            return new SemanticAnnotationOutcome(null, errorCode, attempts, modelCalls, latencyMs, tokenEstimate);
        }
    }

    /** 持久化语义标注：含模型、Prompt、Schema 版本与幂等键所需字段。 */
    public record SemanticAnnotationRecord(
            String annotationId,
            String projectId,
            String documentId,
            String requirementVersion,
            String sourceRevision,
            String sourceChunkId,
            String parentId,
            String windowId,
            int windowIndex,
            int startOffset,
            int endOffset,
            String sourceFile,
            int parentOrder,
            String contentHash,
            String rawText,
            String semanticSummary,
            String semanticText,
            SemanticAnnotationResult result,
            String model,
            String promptVersion,
            String schemaVersion,
            ExtractionStatus extractionStatus,
            ClaimStatus claimStatus,
            Double confidence,
            int attemptCount,
            int modelCalls,
            long latencyMs,
            int tokenEstimate,
            SemanticErrorCode errorCode,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    /** 语义标注构建请求：按项目 / 文档 / 需求版本圈定输入范围。 */
    public record SemanticBuildRequest(
            String projectId,
            String documentId,
            String requirementVersion,
            String collection,
            Boolean retryFailedOnly
    ) {
        public SemanticBuildRequest(String projectId, String documentId, String requirementVersion, String collection) {
            this(projectId, documentId, requirementVersion, collection, null);
        }

        /** 返回以指定规范 projectId 重建的请求（Controller 入口规范化业务项目 ID 用）。 */
        public SemanticBuildRequest withProjectId(String normalizedProjectId) {
            return new SemanticBuildRequest(normalizedProjectId, documentId, requirementVersion,
                    collection, retryFailedOnly);
        }
    }

    /** 构建整体状态：部分失败不能伪装成完整成功。 */
    public enum SemanticBuildStatus { SUCCESS, PARTIAL_FAILURE, FAILED }

    public record SemanticBuildResult(
            String projectId,
            String documentId,
            String requirementVersion,
            String sourceRevision,
            String model,
            String promptVersion,
            String schemaVersion,
            int totalChunks,
            int skippedChunks,
            int completedChunks,
            int failedChunks,
            SemanticBuildStatus status,
            List<String> warnings,
            List<ChunkFailure> failures
    ) {
        public SemanticBuildResult {
            warnings = immutable(warnings);
            failures = immutable(failures);
        }
    }

    /** 单个失败的输入单元（父块或窗口），携带稳定错误码供重试。 */
    public record ChunkFailure(
            String sourceChunkId,
            String windowId,
            String errorCode
    ) {
    }

    /** 一次语义构建的版本化记录：active 构建决定检索可见的 sourceRevision，旧构建保留但非 active。 */
    public record SemanticBuildRecord(
            String buildId,
            String projectId,
            String documentId,
            String requirementVersion,
            String sourceRevision,
            String model,
            String promptVersion,
            String schemaVersion,
            SemanticBuildStatus buildStatus,
            int totalChunks,
            int skippedChunks,
            int completedChunks,
            int failedChunks,
            List<String> warnings,
            Instant startedAt,
            Instant finishedAt,
            boolean active
    ) {
        public SemanticBuildRecord {
            warnings = immutable(warnings);
        }
    }

    /** 构建输入快照：active 查询必须用当前构建的输入集合过滤标注，防止已删除/过期窗口被重新激活。 */
    public record SemanticBuildInput(
            String sourceChunkId,
            String windowId,
            String contentHash
    ) {
    }

    /**
     * 构建状态查询视图：最新一次执行（run）与当前生效代际（generation）分开表述。
     * <ul>
     *   <li>{@code latestRunStatus} / 统计 / runId：最新一次执行；同 buildId 失败重跑时
     *       {@code latestRunStatus=PARTIAL_FAILURE} 且 {@code generationActive=true} 表示
     *       "最新执行失败，但它所属代际（此前成功的同一代际）仍在线"；</li>
     *   <li>{@code activeGeneration*}：当前范围内真正 active 的代际（LEFT JOIN 查询，与最新 run
     *       所属代际无关）——"rev-1 SUCCESS active + rev-2 FAILED inactive" 时
     *       {@code generationActive=false} 但 {@code activeGenerationStatus=SUCCESS}；
     *       无 active 代际时三个字段为 null。</li>
     * </ul>
     */
    public record SemanticBuildStatusView(
            String runId,
            String buildId,
            String projectId,
            String documentId,
            String requirementVersion,
            String sourceRevision,
            String model,
            String promptVersion,
            String schemaVersion,
            SemanticBuildStatus latestRunStatus,
            int totalChunks,
            int skippedChunks,
            int completedChunks,
            int failedChunks,
            List<String> warnings,
            Instant runStartedAt,
            Instant runFinishedAt,
            boolean generationActive,
            String activeGenerationBuildId,
            String activeGenerationSourceRevision,
            SemanticBuildStatus activeGenerationStatus
    ) {
        public SemanticBuildStatusView {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** 兼容旧 {@code SemanticBuildRecord} JSON 字段：旧调用方仍按 buildStatus 消费（需 @JsonProperty 才会出现在序列化结果中）。 */
        @Deprecated
        @com.fasterxml.jackson.annotation.JsonProperty("buildStatus")
        public SemanticBuildStatus buildStatus() {
            return latestRunStatus;
        }

        /** 兼容旧 {@code SemanticBuildRecord} JSON 字段：旧调用方仍按 active 消费（需 @JsonProperty 才会出现在序列化结果中）。 */
        @Deprecated
        @com.fasterxml.jackson.annotation.JsonProperty("active")
        public boolean active() {
            return generationActive;
        }

        /**
         * 兼容旧 {@code SemanticBuildRecord} 时间字段 startedAt：新 DTO 改名为 runStartedAt 后，
         * 已有轮询客户端会直接读到 undefined——必须保留旧 JSON 字段名（Render 层旧脚本仍按 startedAt 消费）。
         */
        @Deprecated
        @com.fasterxml.jackson.annotation.JsonProperty("startedAt")
        public Instant startedAt() {
            return runStartedAt;
        }

        /** 兼容旧 {@code SemanticBuildRecord} 时间字段 finishedAt（见 startedAt 说明）。 */
        @Deprecated
        @com.fasterxml.jackson.annotation.JsonProperty("finishedAt")
        public Instant finishedAt() {
            return runFinishedAt;
        }
    }

    /**
     * 项目/版本级聚合构建状态视图：多文档项目里语义检索按 projectId+version 召回该版本
     * 全部 active 文档，因此状态条必须按同样范围聚合，不能只展示单个 documentId 的状态。
     * <ul>
     *   <li>{@code hasActiveGeneration}：该 project+version 下是否存在任一 active SUCCESS 代际；</li>
     *   <li>{@code activeDocumentCount} / {@code activeDocumentIds}：active 代际覆盖的文档；</li>
     *   <li>{@code latestRun*}：整个范围内最近一次执行（跨文档），用于提示"最新执行失败但仍有在线代际"；</li>
     *   <li>无任何 run 记录时返回 empty（调用方按 404/不可用处理）。</li>
     * </ul>
     */
    public record SemanticBuildAggregateView(
            String projectId,
            String requirementVersion,
            boolean hasActiveGeneration,
            int activeDocumentCount,
            List<String> activeDocumentIds,
            List<String> activeBuildIds,
            String latestRunRunId,
            String latestRunBuildId,
            SemanticBuildStatus latestRunStatus,
            int latestRunTotalChunks,
            int latestRunCompletedChunks,
            int latestRunFailedChunks,
            List<String> latestRunWarnings,
            boolean candidateRetrievalEnabled,
            boolean normativeRetrievalEnabled,
            /** 中（第七批 Review M3）：项目级多源检索开关——关闭时语义候选无法参与 /multi-source/search，
             * 前端据此把“已发布”降级为警示，而不是误导用户以为检索链路可用。 */
            boolean multiSourceEnabledForProject
    ) {
        public SemanticBuildAggregateView {
            activeDocumentIds = immutable(activeDocumentIds);
            activeBuildIds = immutable(activeBuildIds);
            latestRunWarnings = immutable(latestRunWarnings);
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
