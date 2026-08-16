package com.example.requirementrag.evolution.experience;

import java.time.Instant;
import java.util.List;

/**
 * 一次在线检索的可重放经验事件。
 * <p>
 * 记录策略版本、配置哈希、索引版本、每跳策略、候选、反思结果和最终交付状态，
 * 用于失败挖掘和离线策略实验。敏感正文默认不落盘，只保存 hash 和可配置的截断预览。
 * </p>
 *
 * @param schemaVersion      经验事件 schema 版本
 * @param experienceId       全局唯一经验 ID，贯穿日志、反馈和实验
 * @param occurredAt         发生时间
 * @param projectId          项目 ID
 * @param documentId         需求文档 ID，可为 null
 * @param version            业务版本，可为 null
 * @param queryHash          查询哈希，用于聚类和去重
 * @param queryPreview       查询预览，默认截断或关闭
 * @param retrievalProfile   检索画像名
 * @param selectedStrategy   首跳选中的策略名
 * @param executedStrategies 实际执行过的策略名列表
 * @param hops               实际跳数
 * @param candidates         候选快照列表
 * @param finalRanking       最终排名 ID 列表
 * @param evidenceIds        最终证据 ID 列表
 * @param reflectionVerdict  最终反思裁决
 * @param reflectionReasonCode 最终反思 reason code
 * @param outcomeStatus      最终交付状态
 * @param warningCodes       安全 warning code 列表
 * @param diagnostics        阶段诊断快照
 * @param latencyMs          总耗时
 * @param tokenCost          可选的 token 成本
 * @param degradedStages     降级阶段列表
 * @param feedback           用户反馈，可为 null
 * @param policyVersion      使用的策略版本
 * @param configHash         配置哈希
 * @param indexVersion       索引版本
 * @param datasetVersion     关联的评测数据集版本，可为 null
 */
public record RetrievalExperience(
        int schemaVersion,
        String experienceId,
        Instant occurredAt,
        String projectId,
        String documentId,
        String version,
        String queryHash,
        String queryPreview,
        String retrievalProfile,
        String selectedStrategy,
        List<String> executedStrategies,
        int hops,
        List<HopSnapshot> hopDetails,
        List<CandidateSnapshot> candidates,
        List<String> finalRanking,
        List<String> evidenceIds,
        String reflectionVerdict,
        String reflectionReasonCode,
        String outcomeStatus,
        List<String> warningCodes,
        List<StageSnapshot> diagnostics,
        long latencyMs,
        Long tokenCost,
        List<String> degradedStages,
        UserFeedback feedback,
        String policyVersion,
        String configHash,
        String indexVersion,
        String datasetVersion
) {
    public static final int SCHEMA_VERSION = 1;

    public RetrievalExperience {
        executedStrategies = executedStrategies == null ? List.of() : List.copyOf(executedStrategies);
        hopDetails = hopDetails == null ? List.of() : List.copyOf(hopDetails);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        finalRanking = finalRanking == null ? List.of() : List.copyOf(finalRanking);
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        warningCodes = warningCodes == null ? List.of() : List.copyOf(warningCodes);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        degradedStages = degradedStages == null ? List.of() : List.copyOf(degradedStages);
    }

    /** 单跳执行快照：策略、反思裁决、reason code 与耗时。 */
    public record HopSnapshot(
            int hop,
            String strategy,
            String reflectionVerdict,
            String reflectionReasonCode,
            long durationMs
    ) {
    }

    /** 单条候选快照：保存候选 ID、来源通道、原始排名、最终排名和关键分数。 */
    public record CandidateSnapshot(
            String candidateId,
            String channel,
            Integer originalRank,
            Integer finalRank,
            Double score
    ) {
    }

    /** 单个检索阶段诊断快照。 */
    public record StageSnapshot(
            String stage,
            String status,
            String warningCode,
            Long durationMs
    ) {
    }

    /** 用户反馈弱标签，不直接作为 golden truth。 */
    public record UserFeedback(
            String rating,
            List<String> rejectedEvidenceIds,
            String commentPreview,
            Instant feedbackAt
    ) {
        public UserFeedback {
            rejectedEvidenceIds = rejectedEvidenceIds == null ? List.of() : List.copyOf(rejectedEvidenceIds);
        }
    }
}
