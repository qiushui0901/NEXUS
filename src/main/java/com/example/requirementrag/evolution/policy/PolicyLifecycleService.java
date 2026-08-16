package com.example.requirementrag.evolution.policy;

import com.example.requirementrag.evolution.evaluation.EvolutionExperimentRunner;
import com.example.requirementrag.evolution.evaluation.ExperimentManifest;
import com.example.requirementrag.evolution.evaluation.ExperimentReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;

/** 检索策略生命周期服务：Draft → Evaluating → Approved/Rejected → Active。 */
@Service
public class PolicyLifecycleService {

    private static final Map<PolicyStatus, EnumSet<PolicyStatus>> TRANSITIONS = Map.of(
            PolicyStatus.DRAFT, EnumSet.of(PolicyStatus.EVALUATING),
            PolicyStatus.EVALUATING, EnumSet.of(PolicyStatus.APPROVED, PolicyStatus.REJECTED),
            PolicyStatus.APPROVED, EnumSet.of(PolicyStatus.ACTIVE, PolicyStatus.REJECTED),
            PolicyStatus.REJECTED, EnumSet.of(PolicyStatus.EVALUATING),
            PolicyStatus.ACTIVE, EnumSet.of(PolicyStatus.ROLLED_BACK)
    );

    private final RetrievalPolicyRegistry registry;
    private final PolicyPromotionGate promotionGate;
    private final EvolutionExperimentRunner experimentRunner;

    @Autowired
    public PolicyLifecycleService(RetrievalPolicyRegistry registry,
                                  PolicyPromotionGate promotionGate,
                                  EvolutionExperimentRunner experimentRunner) {
        this.registry = registry;
        this.promotionGate = promotionGate;
        this.experimentRunner = experimentRunner;
    }

    /** 兼容构造器：供未接入门禁的旧测试/调用方使用（门禁会拒绝审批）。 */
    public PolicyLifecycleService(RetrievalPolicyRegistry registry) {
        this(registry, new PolicyPromotionGate(), null);
    }

    public RetrievalPolicy createDraft(String policyId, String version,
                                       java.util.Map<String, String> selectorRules,
                                       java.util.Map<String, Double> rankingWeights,
                                       java.util.Map<String, Integer> thresholds,
                                       java.util.Map<String, Boolean> featureFlags,
                                       String parentVersion) {
        RetrievalPolicy policy = new RetrievalPolicy(policyId, version, PolicyStatus.DRAFT,
                selectorRules, rankingWeights, thresholds, featureFlags, parentVersion, null,
                RetrievalPolicy.checksum(policyId, version, selectorRules, rankingWeights,
                        thresholds, featureFlags),
                Instant.now());
        registry.save(policy);
        return policy;
    }

    public RetrievalPolicy submitEvaluating(String policyId, String version) {
        return transition(policyId, version, PolicyStatus.EVALUATING, null);
    }

    /**
     * 批准策略：必须已处于 EVALUATING，且关联实验报告存在并通过 Promotion Gate。
     *
     * @param experimentId 用于门禁判断的离线实验报告 ID
     */
    public RetrievalPolicy approve(String policyId, String version, String experimentId) {
        RetrievalPolicy current = require(policyId, version);
        ensureTransitionAllowed(current.status(), PolicyStatus.APPROVED);
        if (experimentRunner == null || experimentId == null || experimentId.isBlank()) {
            throw new IllegalArgumentException("批准策略必须提供实验报告 ID");
        }
        ExperimentReport report = experimentRunner.find(experimentId);
        if (report == null || report.manifest() == null) {
            throw new IllegalArgumentException("实验报告不存在: " + experimentId);
        }
        ExperimentManifest manifest = report.manifest();
        if (!policyId.equals(manifest.candidatePolicyId())
                || !version.equals(manifest.candidatePolicyVersion())) {
            throw new IllegalArgumentException("实验报告与候选策略不匹配");
        }
        if (!promotionGate.passes(report)) {
            throw new IllegalArgumentException("策略未通过 Promotion Gate，不能批准");
        }
        return transition(policyId, version, PolicyStatus.APPROVED, experimentId);
    }

    public RetrievalPolicy reject(String policyId, String version) {
        return transition(policyId, version, PolicyStatus.REJECTED, null);
    }

    public RetrievalPolicy activate(String policyId, String version) {
        RetrievalPolicy current = require(policyId, version);
        ensureTransitionAllowed(current.status(), PolicyStatus.ACTIVE);
        registry.activate(policyId, version);
        RetrievalPolicy activated = new RetrievalPolicy(current.policyId(), current.version(),
                PolicyStatus.ACTIVE, current.selectorRules(), current.rankingWeights(),
                current.thresholds(), current.featureFlags(), current.parentVersion(),
                current.experimentId(), current.checksum(), current.createdAt());
        registry.save(activated);
        return activated;
    }

    private RetrievalPolicy transition(String policyId, String version, PolicyStatus target,
                                       String experimentId) {
        RetrievalPolicy current = require(policyId, version);
        ensureTransitionAllowed(current.status(), target);
        RetrievalPolicy updated = new RetrievalPolicy(current.policyId(), current.version(), target,
                current.selectorRules(), current.rankingWeights(), current.thresholds(),
                current.featureFlags(), current.parentVersion(),
                experimentId != null ? experimentId : current.experimentId(),
                current.checksum(), current.createdAt());
        registry.save(updated);
        return updated;
    }

    private RetrievalPolicy require(String policyId, String version) {
        RetrievalPolicy current = registry.find(policyId, version);
        if (current == null) {
            throw new IllegalArgumentException("检索策略不存在");
        }
        return current;
    }

    private void ensureTransitionAllowed(PolicyStatus from, PolicyStatus to) {
        if (!TRANSITIONS.getOrDefault(from, EnumSet.noneOf(PolicyStatus.class)).contains(to)) {
            throw new IllegalArgumentException("非法策略状态转换: " + from + " -> " + to);
        }
    }
}
