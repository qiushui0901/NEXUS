package com.example.requirementrag.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 需求语义图金标评测领域模型（v0.2）。
 *
 * <p>支持实体/关系/Claim/存疑/代码事实五类 gold 与预测，按场景聚合 Precision/Recall/F1。
 *
 * <p>从 v0.2 修订：
 * <ul>
 *   <li>Gold 增加显式 {@code decision}（type/status/publication/evidenceIds），评测不再从 scenario 硬编码期望；</li>
 *   <li>保留窗口级输入 {@code windows}，跨窗口评测走“逐窗口抽取 + 合并 + 整合”，而不是拼接一段文本；</li>
 *   <li>增加 {@code codeFactInputs} 明确代码事实输入契约（评测“给定代码事实能否忠实回写”，而非让 LLM 猜）；</li>
 *   <li>{@code evidenceItems} 支持 evidence 实际回查与 offset/claim 支持校验。</li>
 * </ul>
 */
public final class RequirementGraphGoldModels {
    private RequirementGraphGoldModels() {
    }

    public record GoldCase(
            String caseId,
            String scenario,
            String inputText,
            List<GoldWindow> windows,
            List<GoldEntity> entities,
            List<GoldRelation> relations,
            List<GoldClaim> claims,
            List<GoldUncertainty> uncertainties,
            List<GoldCodeFact> codeFacts,
            GoldDecision decision,
            List<GoldCodeFact> codeFactInputs,
            List<GoldEvidenceItem> evidenceItems,
            int totalEvidenceItems,
            int traceableEvidenceItems,
            String projectId,
            String documentId,
            String requirementVersion,
            String annotationStatus
    ) {
        public GoldCase {
            windows = windows == null ? List.of() : List.copyOf(windows);
            entities = entities == null ? List.of() : List.copyOf(entities);
            relations = relations == null ? List.of() : List.copyOf(relations);
            claims = claims == null ? List.of() : List.copyOf(claims);
            uncertainties = uncertainties == null ? List.of() : List.copyOf(uncertainties);
            codeFacts = codeFacts == null ? List.of() : List.copyOf(codeFacts);
            codeFactInputs = codeFactInputs == null ? List.of() : List.copyOf(codeFactInputs);
            evidenceItems = evidenceItems == null ? List.of() : List.copyOf(evidenceItems);
            projectId = projectId == null ? "" : projectId;
            documentId = documentId == null ? "" : documentId;
            requirementVersion = requirementVersion == null ? "" : requirementVersion;
            annotationStatus = annotationStatus == null ? "" : annotationStatus;
        }

        /** 兼容旧十参数构造：无窗口、无显式 decision、无代码事实输入、无证据明细、无项目上下文。 */
        public GoldCase(String caseId, String scenario, String inputText,
                        List<GoldEntity> entities, List<GoldRelation> relations, List<GoldClaim> claims,
                        List<GoldUncertainty> uncertainties, List<GoldCodeFact> codeFacts,
                        int totalEvidenceItems, int traceableEvidenceItems) {
            this(caseId, scenario, inputText, List.of(), entities, relations, claims, uncertainties,
                    codeFacts, null, List.of(), List.of(), totalEvidenceItems, traceableEvidenceItems,
                    "", "", "", "");
        }

        /** 兼容旧窗口构造（无 decision/代码事实输入/证据明细/项目上下文）。 */
        public GoldCase(String caseId, String scenario, String inputText, List<GoldWindow> windows,
                        List<GoldEntity> entities, List<GoldRelation> relations, List<GoldClaim> claims,
                        List<GoldUncertainty> uncertainties, List<GoldCodeFact> codeFacts,
                        int totalEvidenceItems, int traceableEvidenceItems) {
            this(caseId, scenario, inputText, windows, entities, relations, claims, uncertainties,
                    codeFacts, null, List.of(), List.of(), totalEvidenceItems, traceableEvidenceItems,
                    "", "", "", "");
        }
    }

    /** 评测期望决策：不再从 scenario 推导，逐字段精确比较。 */
    public record GoldDecision(String type, String status, String publication, List<String> evidenceIds) {
        public GoldDecision {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            type = type == null ? "" : type;
            status = status == null ? "" : status;
            publication = publication == null ? "" : publication;
        }
    }

    /** 单窗口输入：保留窗口独立抽取所必需的元数据。 */
    public record GoldWindow(String windowId, int index, String parentId, int parentOrder, String filename,
                             int startOffset, int endOffset, String contentHash, String text) {
    }

    /** 金标证据明细：供 field completeness / source match / offset validity / claim support 校验。 */
    public record GoldEvidenceItem(
            String evidenceId,
            String sourceType,
            String sourceFile,
            String quote,
            boolean hasOffset,
            int startOffset,
            int endOffset,
            boolean hasWindowId,
            String windowId,
            boolean hasContentHash,
            String contentHash,
            String expected
    ) {
        public GoldEvidenceItem {
            quote = quote == null ? "" : quote;
            sourceFile = sourceFile == null ? "" : sourceFile;
            sourceType = sourceType == null ? "" : sourceType;
        }
    }

    public record GoldEntity(String id, String type, String canonicalName, List<String> aliases) {
        public GoldEntity {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    public record GoldRelation(String subject, String predicate, String object, List<String> evidenceIds) {
        public GoldRelation {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    public record GoldClaim(String factKey, String value, String certainty, List<String> evidenceIds) {
        public GoldClaim {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    public record GoldUncertainty(String id, String status, String type, String question) {
    }

    public record GoldCodeFact(String repositoryId, String commitSha, String factKey, String value,
                               List<String> symbolNames) {
        public GoldCodeFact {
            symbolNames = symbolNames == null ? List.of() : List.copyOf(symbolNames);
        }
    }

    /** 预测实体：携带类型，评测才能区分同名的不同类型实体。 */
    public record PredictedEntity(String type, String canonicalName, List<String> aliases) {
        public PredictedEntity {
            type = type == null ? "" : type;
            canonicalName = canonicalName == null ? "" : canonicalName;
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }

        public PredictedEntity(String type, String canonicalName) {
            this(type, canonicalName, List.of());
        }

        public static PredictedEntity untyped(String canonicalName) {
            return new PredictedEntity("", canonicalName, List.of());
        }

        public String name() {
            return canonicalName;
        }
    }

    public record Prediction(
            Set<PredictedEntity> entities,
            List<PredictedRelation> relations,
            List<PredictedClaim> claims,
            List<String> uncertainties,
            List<PredictedCodeFact> codeFacts,
            DriftDecision driftDecision,
            PublicationDecision publicationDecision,
            PredictionStatus status,
            String errorCode,
            long latencyMs,
            int retryCount
    ) {
        public Prediction {
            entities = entities == null ? Set.of() : Set.copyOf(entities);
            relations = relations == null ? List.of() : List.copyOf(relations);
            claims = claims == null ? List.of() : List.copyOf(claims);
            uncertainties = uncertainties == null ? List.of() : List.copyOf(uncertainties);
            codeFacts = codeFacts == null ? List.of() : List.copyOf(codeFacts);
            driftDecision = driftDecision == null ? new DriftDecision("", "", "", List.of()) : driftDecision;
            publicationDecision = publicationDecision == null ? PublicationDecision.NOT_PUBLISHED : publicationDecision;
            status = status == null ? PredictionStatus.SUCCESS : status;
            errorCode = errorCode == null ? "" : errorCode;
        }

        /** 兼容旧实体字符串构造：包装为未携带类型的 {@link PredictedEntity}。 */
        public Prediction(Set<String> entities, List<PredictedRelation> relations,
                          List<PredictedClaim> claims, List<String> uncertainties) {
            this(wrapEntities(entities), relations, claims, uncertainties, List.of(),
                    new DriftDecision("", "", "", List.of()), PublicationDecision.NOT_PUBLISHED,
                    PredictionStatus.SUCCESS, "", 0, 0);
        }

        private static Set<PredictedEntity> wrapEntities(Set<String> entities) {
            if (entities == null) return Set.of();
            Set<PredictedEntity> result = new java.util.LinkedHashSet<>();
            for (String entity : entities) {
                if (entity != null && !entity.isBlank()) result.add(PredictedEntity.untyped(entity.trim()));
            }
            return Set.copyOf(result);
        }

        public static Prediction empty() {
            return new Prediction(Set.of(), List.of(), List.of(), List.of(), List.of(),
                    new DriftDecision("", "", "", List.of()), PublicationDecision.NOT_PUBLISHED,
                    PredictionStatus.EMPTY_RESULT, "", 0, 0);
        }

        /** 保留其余字段，只替换实际重试次数（重试统计不得伪造）。 */
        public Prediction withRetryCount(int newRetryCount) {
            return new Prediction(entities, relations, claims, uncertainties, codeFacts,
                    driftDecision, publicationDecision, status, errorCode, latencyMs, newRetryCount);
        }

        /** 保留其余字段，只替换耗时（用于外层重试循环统一记录成功/失败耗时）。 */
        public Prediction withLatency(long newLatencyMs) {
            return new Prediction(entities, relations, claims, uncertainties, codeFacts,
                    driftDecision, publicationDecision, status, errorCode, newLatencyMs, retryCount);
        }
    }

    /** 预测失败/状态码，避免把“真没抽到”“超时”“限流”“JSON失败”混为一谈。 */
    public enum PredictionStatus {
        SUCCESS,
        EMPTY_RESULT,
        MODEL_TIMEOUT,
        MODEL_RATE_LIMITED,
        JSON_PARSE_FAILED,
        SCHEMA_INVALID,
        FAILURE
    }

    /** 预测代码事实：必须携带仓库、commit、factKey、值与符号。 */
    public record PredictedCodeFact(String repositoryId, String commitSha, String factKey, String value,
                                    List<String> symbols) {
        public PredictedCodeFact {
            symbols = symbols == null ? List.of() : List.copyOf(symbols);
        }
    }

    /** 漂移/冲突/发布决策：用于 DOCUMENT_DRIFT / CONFLICT / OPEN_DOUBT 场景。 */
    public record DriftDecision(String type, String status, String reason, List<String> evidenceIds) {
        public DriftDecision {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            type = type == null ? "" : type;
            status = status == null ? "" : status;
            reason = reason == null ? "" : reason;
        }
    }

    public enum PublicationDecision {
        PUBLISH,
        REVIEW_REQUIRED,
        PRESERVE_CONFLICT,
        NOT_PUBLISHED
    }

    public record PredictedRelation(String source, String target, String predicate) {
    }

    public record PredictedClaim(String factKey, String value) {
    }

    public record ScenarioMetrics(
            String scenario,
            int cases,
            double entityPrecision,
            double entityRecall,
            double entityF1,
            double relationPrecision,
            double relationRecall,
            double relationF1,
            double claimPrecision,
            double claimRecall,
            double claimF1,
            double negativeErrorRate,
            double uncertaintyRecall,
            double codeFactRecall,
            double codeFactPrecision,
            double codeFactF1,
            double driftDecisionAccuracy
    ) {
    }

    public record GoldEvalReport(
            int totalCases,
            int extractionCases,
            int retrievalCases,
            List<ScenarioMetrics> scenarios,
            ScenarioMetrics overall,
            double goldEvidenceFieldCompletenessRate,
            Map<String, Object> extras
    ) {
        public GoldEvalReport {
            scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
            extras = extras == null ? Map.of() : Map.copyOf(extras);
        }
    }
}
