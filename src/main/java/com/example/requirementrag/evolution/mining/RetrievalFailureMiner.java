package com.example.requirementrag.evolution.mining;

import com.example.requirementrag.evolution.experience.RetrievalExperience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 从检索经验中挖掘高价值失败候选。
 * <p>
 * 只生成候选，不自动生成正确答案；最终 relevant ID 必须经人工审核。
 * </p>
 */
@Service
public class RetrievalFailureMiner {

    private static final Logger log = LoggerFactory.getLogger(RetrievalFailureMiner.class);
    private static final long HIGH_LATENCY_THRESHOLD_MS = 5_000;

    private final EvaluationCandidateStore candidateStore;
    private final List<FailureRule> rules = defaultRules();

    public RetrievalFailureMiner(EvaluationCandidateStore candidateStore) {
        this.candidateStore = candidateStore;
    }

    /** 对一批经验执行失败挖掘，保存并返回新生成的候选。 */
    public List<EvaluationCandidate> mine(List<RetrievalExperience> experiences) {
        Map<String, List<RetrievalExperience>> groups = new LinkedHashMap<>();
        for (RetrievalExperience experience : experiences) {
            if (experience.queryPreview() == null || experience.queryPreview().isBlank()) {
                // query preview 关闭时无法形成可评测的真实 query，跳过，避免把哈希当查询
                continue;
            }
            for (FailureRule rule : rules) {
                if (rule.predicate().test(experience)) {
                    String key = experience.queryHash() + "|" + rule.failureType().name()
                            + "|" + safe(experience.indexVersion());
                    groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(experience);
                    break;
                }
            }
        }
        List<EvaluationCandidate> existing = candidateStore.findAll();
        java.util.Set<String> existingKeys = new java.util.HashSet<>();
        for (EvaluationCandidate candidate : existing) {
            existingKeys.add(dedupKey(candidate));
        }
        List<EvaluationCandidate> created = new ArrayList<>();
        for (Map.Entry<String, List<RetrievalExperience>> entry : groups.entrySet()) {
            if (existingKeys.contains(entry.getKey())) {
                continue;
            }
            List<RetrievalExperience> cluster = entry.getValue();
            RetrievalExperience first = cluster.get(0);
            FailureType failureType = classify(first);
            if (failureType == null) {
                continue;
            }
            EvaluationCandidate candidate = new EvaluationCandidate(
                    UUID.randomUUID().toString(),
                    first.experienceId(),
                    first.queryHash(),
                    first.queryPreview(),
                    failureType,
                    failureReason(first, failureType),
                    safe(first.indexVersion()),
                    List.of(),
                    priority(cluster.size(), failureType, first),
                    ReviewStatus.DRAFT,
                    null,
                    null
            );
            candidateStore.save(candidate);
            existingKeys.add(entry.getKey());
            created.add(candidate);
        }
        log.info("Failure miner created {} candidates from {} experiences", created.size(), experiences.size());
        return List.copyOf(created);
    }

    private static String dedupKey(EvaluationCandidate candidate) {
        return candidate.queryHash() + "|" + candidate.failureType().name()
                + "|" + safe(candidate.indexVersion());
    }

    private static List<FailureRule> defaultRules() {
        return List.of(
                new FailureRule(FailureType.CORE_STAGE_FAILED, "核心阶段失败",
                        e -> "FAILED".equals(e.outcomeStatus())),
                new FailureRule(FailureType.NO_HIT, "无结果",
                        e -> "NO_RESULTS".equals(e.outcomeStatus())),
                new FailureRule(FailureType.DEGRADED_RESULT, "降级结果",
                        e -> "DEGRADED".equals(e.outcomeStatus()) || !e.warningCodes().isEmpty()),
                new FailureRule(FailureType.HIGH_LATENCY, "高延迟",
                        e -> e.latencyMs() >= HIGH_LATENCY_THRESHOLD_MS),
                new FailureRule(FailureType.DUPLICATE_ONLY, "重复证据",
                        e -> "DUPLICATE_ONLY".equals(e.reflectionReasonCode())),
                new FailureRule(FailureType.SINGLE_SIDE_ONLY, "单侧证据",
                        e -> "SINGLE_SIDE_ONLY".equals(e.reflectionReasonCode())),
                new FailureRule(FailureType.USER_REJECTED, "用户拒绝",
                        e -> e.feedback() != null && e.feedback().rating() != null
                                && e.feedback().rating().startsWith("reject")),
                new FailureRule(FailureType.INDEX_STALENESS, "索引过期",
                        e -> e.warningCodes().contains("INDEX_STALENESS"))
        );
    }

    private static FailureType classify(RetrievalExperience experience) {
        for (FailureRule rule : defaultRules()) {
            if (rule.predicate().test(experience)) {
                return rule.failureType();
            }
        }
        return null;
    }

    private static String failureReason(RetrievalExperience experience, FailureType failureType) {
        String reason = experience.reflectionReasonCode();
        return reason == null || reason.isBlank() ? failureType.name() : reason;
    }

    private static double priority(int occurrenceCount, FailureType failureType, RetrievalExperience experience) {
        double severity = switch (failureType) {
            case CORE_STAGE_FAILED, INDEX_STALENESS -> 3.0;
            case USER_REJECTED, HIGH_LATENCY -> 2.0;
            default -> 1.0;
        };
        double reproducibility = experience.hops() > 0 ? 1.0 : 0.5;
        double duplicatePenalty = occurrenceCount > 10 ? 2.0 : 1.0;
        return severity * occurrenceCount * reproducibility / duplicatePenalty;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
