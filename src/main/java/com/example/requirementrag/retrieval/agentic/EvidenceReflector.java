package com.example.requirementrag.retrieval.agentic;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.RagOutcomeStatus;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 规则版证据反射器：对策略结果做自评，输出"是否足够"的裁决与稳定 reason code。
 * 信号包括核心阶段状态、需求命中阈值、重复证据比例与需求/代码双侧覆盖。
 * 后续可替换为基于 LLM 的反射实现，接口不变。
 */
@Component
public class EvidenceReflector {

    /** 默认需求证据最小命中数：低于该值视为证据不足。 */
    public static final int DEFAULT_MIN_REQUIREMENT_HITS = 1;

    /** 需求命中全部来自同一父块时视为重复证据（单一来源不构成充分证据）。 */
    public static final int MIN_UNIQUE_PARENTS = 2;

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
     * @return 裁决与稳定 reason code：CONFIDENT（充分）/ INSUFFICIENT（不足，应补检）/
     *         NOT_RETRIEVABLE（核心失败，应降级交付）
     */
    public ReflectionResult evaluate(StrategyResult result) {
        if (result == null || result.status() == RagOutcomeStatus.FAILED) {
            return new ReflectionResult(ReflectionVerdict.NOT_RETRIEVABLE, "CORE_STAGE_FAILED");
        }
        boolean needsRequirements = result.bundle() != null
                && result.bundle().profile().usesRequirementEvidence();
        if (needsRequirements) {
            int hits = result.requirementHitCount();
            if (hits < minRequirementHits) {
                return new ReflectionResult(ReflectionVerdict.INSUFFICIENT, "BELOW_MIN_HITS");
            }
            if (hits >= MIN_UNIQUE_PARENTS && uniqueParents(result) < MIN_UNIQUE_PARENTS) {
                return new ReflectionResult(ReflectionVerdict.INSUFFICIENT, "DUPLICATE_ONLY");
            }
        }
        boolean needsCode = result.bundle() != null && result.bundle().profile().usesCodeEvidence();
        if (needsCode && result.requirementHitCount() > 0 && result.codeHitCount() == 0) {
            return new ReflectionResult(ReflectionVerdict.INSUFFICIENT, "SINGLE_SIDE_ONLY");
        }
        return new ReflectionResult(ReflectionVerdict.CONFIDENT, "HIT_THRESHOLD_MET");
    }

    /** 需求证据中不同父块的个数（重复证据判定信号）。 */
    private int uniqueParents(StrategyResult result) {
        List<ChunkRecord> evidence = result.bundle() == null ? List.of()
                : result.bundle().requirementEvidence();
        Set<String> parents = new HashSet<>();
        for (ChunkRecord chunk : evidence) {
            String parent = chunk.parentId() == null || chunk.parentId().isBlank()
                    ? chunk.filename() + ":" + chunk.parentOrder() : chunk.parentId();
            parents.add(parent);
        }
        return parents.size();
    }

    /** 反射裁决：CONFIDENT（充分）/ INSUFFICIENT（不足）/ NOT_RETRIEVABLE（不可救）。 */
    public enum ReflectionVerdict {
        CONFIDENT,
        INSUFFICIENT,
        NOT_RETRIEVABLE
    }

    /** 反射结果：裁决 + 稳定 reason code（供评测与监控聚合）。 */
    public record ReflectionResult(ReflectionVerdict verdict, String reasonCode) {
    }
}
