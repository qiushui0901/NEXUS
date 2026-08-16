package com.example.requirementrag.evolution.policy;

import org.springframework.stereotype.Service;

import java.time.Instant;

/** 检索策略生命周期服务：Draft → Evaluating → Approved/Rejected → Active。 */
@Service
public class PolicyLifecycleService {

    private final RetrievalPolicyRegistry registry;

    public PolicyLifecycleService(RetrievalPolicyRegistry registry) {
        this.registry = registry;
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
        return transition(policyId, version, PolicyStatus.EVALUATING);
    }

    public RetrievalPolicy approve(String policyId, String version) {
        return transition(policyId, version, PolicyStatus.APPROVED);
    }

    public RetrievalPolicy reject(String policyId, String version) {
        return transition(policyId, version, PolicyStatus.REJECTED);
    }

    public RetrievalPolicy activate(String policyId, String version) {
        RetrievalPolicy current = registry.find(policyId, version);
        if (current == null) {
            throw new IllegalArgumentException("检索策略不存在");
        }
        registry.activate(policyId, version);
        RetrievalPolicy activated = new RetrievalPolicy(current.policyId(), current.version(),
                PolicyStatus.ACTIVE, current.selectorRules(), current.rankingWeights(),
                current.thresholds(), current.featureFlags(), current.parentVersion(),
                current.experimentId(), current.checksum(), current.createdAt());
        registry.save(activated);
        return activated;
    }

    private RetrievalPolicy transition(String policyId, String version, PolicyStatus target) {
        RetrievalPolicy current = registry.find(policyId, version);
        if (current == null) {
            throw new IllegalArgumentException("检索策略不存在");
        }
        RetrievalPolicy updated = new RetrievalPolicy(current.policyId(), current.version(), target,
                current.selectorRules(), current.rankingWeights(), current.thresholds(),
                current.featureFlags(), current.parentVersion(), current.experimentId(),
                current.checksum(), current.createdAt());
        registry.save(updated);
        return updated;
    }
}
