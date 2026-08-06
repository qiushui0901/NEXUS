package com.example.requirementrag.retrieval.agentic;

import com.example.requirementrag.model.RagOutcomeStatus;
import org.springframework.stereotype.Component;

/**
 * 规则版证据反射器：对策略结果做自评，输出"是否足够"的裁决。
 * 规则版用可配置阈值与状态信号替代 LLM 反射标记，作为 Agentic 循环的第一版自评器；
 * 后续可替换为基于 LLM 的反射实现，接口不变。
 */
@Component
public class EvidenceReflector {

    /** 默认需求证据最小命中数：低于该值视为证据不足。 */
    public static final int DEFAULT_MIN_REQUIREMENT_HITS = 1;

    private final int minRequirementHits;

    public EvidenceReflector() {
        this(DEFAULT_MIN_REQUIREMENT_HITS);
    }

    public EvidenceReflector(int minRequirementHits) {
        this.minRequirementHits = minRequirementHits;
    }

    /**
     * 评估一次策略执行结果。
     *
     * @param result 策略产出（含命中统计与状态）
     * @return CONFIDENT：证据充分，可终止循环；INSUFFICIENT：证据不足，应补检/再检；
     *         NOT_RETRIEVABLE：核心阶段失败，继续检索无意义，应降级交付
     */
    public ReflectionVerdict evaluate(StrategyResult result) {
        if (result == null || result.status() == RagOutcomeStatus.FAILED) {
            return ReflectionVerdict.NOT_RETRIEVABLE;
        }
        boolean needsRequirements = result.bundle() != null
                && result.bundle().profile().usesRequirementEvidence();
        if (needsRequirements && result.requirementHitCount() < minRequirementHits) {
            return ReflectionVerdict.INSUFFICIENT;
        }
        return ReflectionVerdict.CONFIDENT;
    }

    /** 反射裁决：CONFIDENT（充分）/ INSUFFICIENT（不足）/ NOT_RETRIEVABLE（不可救）。 */
    public enum ReflectionVerdict {
        CONFIDENT,
        INSUFFICIENT,
        NOT_RETRIEVABLE
    }
}
