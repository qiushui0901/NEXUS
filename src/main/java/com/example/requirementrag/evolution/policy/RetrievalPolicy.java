package com.example.requirementrag.evolution.policy;

import java.time.Instant;
import java.util.Map;

/**
 * 版本化检索策略。
 * <p>
 * 策略只描述可配置行为，不包含可执行脚本。参数必须通过 allowlist 校验，
 * 所有变更通过不可变版本 + 原子激活引用发布。
 * </p>
 *
 * @param policyId       策略 ID
 * @param version        策略版本（同一 policyId 下递增）
 * @param status         生命周期状态
 * @param selectorRules  查询类型到首跳策略的映射
 * @param rankingWeights 融合/重排权重
 * @param thresholds     阈值（最大跳数、反思阈值、topK 等）
 * @param featureFlags   功能开关
 * @param parentVersion  父策略版本，可为 null
 * @param experimentId   关联实验 ID，可为 null
 * @param checksum       策略内容校验和
 * @param createdAt      创建时间
 */
public record RetrievalPolicy(
        String policyId,
        String version,
        PolicyStatus status,
        Map<String, String> selectorRules,
        Map<String, Double> rankingWeights,
        Map<String, Integer> thresholds,
        Map<String, Boolean> featureFlags,
        String parentVersion,
        String experimentId,
        String checksum,
        Instant createdAt
) {
    public RetrievalPolicy {
        selectorRules = selectorRules == null ? Map.of() : Map.copyOf(selectorRules);
        rankingWeights = rankingWeights == null ? Map.of() : Map.copyOf(rankingWeights);
        thresholds = thresholds == null ? Map.of() : Map.copyOf(thresholds);
        featureFlags = featureFlags == null ? Map.of() : Map.copyOf(featureFlags);
    }

    /** 生成策略内容校验和（简单稳定字符串，不用于安全场景）。 */
    public static String checksum(String policyId, String version, Map<String, String> selectorRules,
                                  Map<String, Double> rankingWeights, Map<String, Integer> thresholds,
                                  Map<String, Boolean> featureFlags) {
        String raw = policyId + "|" + version + "|" + selectorRules + "|" + rankingWeights
                + "|" + thresholds + "|" + featureFlags;
        return Integer.toHexString(raw.hashCode());
    }
}
