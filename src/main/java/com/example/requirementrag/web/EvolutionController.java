package com.example.requirementrag.web;

import com.example.requirementrag.evolution.evaluation.EvaluationCaseReviewService;
import com.example.requirementrag.evolution.evaluation.EvaluationDataset;
import com.example.requirementrag.evolution.evaluation.EvaluationDatasetRegistry;
import com.example.requirementrag.evolution.evaluation.EvolutionExperimentRunner;
import com.example.requirementrag.evolution.evaluation.ExperimentReport;
import com.example.requirementrag.evolution.mining.EvaluationCandidate;
import com.example.requirementrag.evolution.mining.ReviewStatus;
import com.example.requirementrag.evolution.policy.PolicyLifecycleService;
import com.example.requirementrag.evolution.policy.RetrievalPolicy;
import com.example.requirementrag.evolution.policy.RetrievalPolicyRegistry;
import com.example.requirementrag.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 自进化 RAG 的候选审核、数据集、策略与实验 API。 */
@RestController
@RequestMapping("/api/evolution")
public class EvolutionController {

    private final EvaluationCaseReviewService reviewService;
    private final EvaluationDatasetRegistry datasetRegistry;
    private final RetrievalPolicyRegistry policyRegistry;
    private final PolicyLifecycleService policyLifecycleService;
    private final EvolutionExperimentRunner experimentRunner;
    private final ProjectAccessGuard accessGuard;

    public EvolutionController(EvaluationCaseReviewService reviewService,
                               EvaluationDatasetRegistry datasetRegistry,
                               RetrievalPolicyRegistry policyRegistry,
                               PolicyLifecycleService policyLifecycleService,
                               EvolutionExperimentRunner experimentRunner,
                               ProjectAccessGuard accessGuard) {
        this.reviewService = reviewService;
        this.datasetRegistry = datasetRegistry;
        this.policyRegistry = policyRegistry;
        this.policyLifecycleService = policyLifecycleService;
        this.experimentRunner = experimentRunner;
        this.accessGuard = accessGuard;
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/candidates")
    public List<EvaluationCandidate> candidates() {
        return reviewService.listCandidates();
    }

    @RequiresPermission(Permission.WRITE)
    @PostMapping("/candidates/{candidateId}/review")
    public EvaluationCandidate review(@PathVariable String candidateId,
                                      @RequestBody ReviewCandidateRequest request, HttpServletRequest httpRequest) {
        ReviewStatus target = switch (request.action().toLowerCase()) {
            case "submit" -> ReviewStatus.IN_REVIEW;
            case "approve" -> ReviewStatus.APPROVED;
            case "reject" -> ReviewStatus.REJECTED;
            default -> throw new IllegalArgumentException("Unsupported review action: " + request.action());
        };
        return reviewService.updateAndTransition(candidateId, target,
                request.relevantIds(), request.queryPreview(),
                accessGuard.currentUser(httpRequest).username());
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/datasets")
    public List<EvaluationDataset> datasets() {
        return datasetRegistry.list();
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/datasets/active")
    public EvaluationDataset activeDataset() {
        return datasetRegistry.active();
    }

    @RequiresPermission(Permission.WRITE)
    @PostMapping("/datasets/publish")
    public EvaluationDataset publishDataset(@RequestParam(required = false) String version) {
        return reviewService.publishApproved(version);
    }

    @RequiresPermission(Permission.WRITE)
    @PostMapping("/datasets/{version}/rollback")
    public EvaluationDataset rollbackDataset(@PathVariable String version) {
        return reviewService.rollbackDataset(version);
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/policies")
    public List<RetrievalPolicy> policies() {
        return policyRegistry.list();
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/policies/active")
    public RetrievalPolicy activePolicy() {
        return policyRegistry.active();
    }

    @RequiresPermission(Permission.WRITE)
    @PostMapping("/policies")
    public RetrievalPolicy createPolicy(@RequestBody CreatePolicyRequest request) {
        return policyLifecycleService.createDraft(request.policyId(), request.version(),
                request.selectorRules() == null ? Map.of() : request.selectorRules(),
                request.rankingWeights() == null ? Map.of() : request.rankingWeights(),
                request.thresholds() == null ? Map.of() : request.thresholds(),
                request.featureFlags() == null ? Map.of() : request.featureFlags(),
                request.parentVersion());
    }

    @RequiresPermission(Permission.WRITE)
    @PostMapping("/policies/{policyId}/{version}/evaluate")
    public RetrievalPolicy evaluatePolicy(@PathVariable String policyId, @PathVariable String version) {
        return policyLifecycleService.submitEvaluating(policyId, version);
    }

    @RequiresPermission(Permission.OPERATE)
    @PostMapping("/policies/{policyId}/{version}/approve")
    public RetrievalPolicy approvePolicy(@PathVariable String policyId, @PathVariable String version,
                                         @RequestParam String experimentId) {
        return policyLifecycleService.approve(policyId, version, experimentId);
    }

    @RequiresPermission(Permission.OPERATE)
    @PostMapping("/policies/{policyId}/{version}/activate")
    public RetrievalPolicy activatePolicy(@PathVariable String policyId, @PathVariable String version) {
        return policyLifecycleService.activate(policyId, version);
    }

    @RequiresPermission(Permission.OPERATE)
    @PostMapping("/experiments")
    public ExperimentReport runExperiment(@RequestBody RunExperimentRequest request) {
        EvaluationDataset dataset = datasetRegistry.find(request.datasetVersion());
        if (dataset == null) {
            throw new IllegalArgumentException("数据集版本不存在: " + request.datasetVersion());
        }
        RetrievalPolicy active = policyRegistry.active();
        if (active == null) {
            throw new IllegalArgumentException("当前没有 ACTIVE 基线策略，不能运行实验");
        }
        RetrievalPolicy baseline = policyRegistry.find(request.baselinePolicyId(), request.baselinePolicyVersion());
        RetrievalPolicy candidate = policyRegistry.find(request.candidatePolicyId(), request.candidatePolicyVersion());
        if (baseline == null || candidate == null) {
            throw new IllegalArgumentException("基线或候选策略不存在");
        }
        if (!active.policyId().equals(baseline.policyId())
                || !active.version().equals(baseline.version())) {
            throw new IllegalArgumentException("实验基线必须是当前 ACTIVE 策略");
        }
        if (baseline.policyId().equals(candidate.policyId())
                && baseline.version().equals(candidate.version())) {
            throw new IllegalArgumentException("候选策略不能与基线策略相同");
        }
        if (request.indexVersion() == null || request.indexVersion().isBlank()
                || "unknown".equals(request.indexVersion())) {
            throw new IllegalArgumentException("实验必须绑定明确的 indexVersion");
        }
        if (request.modelVersion() == null || request.modelVersion().isBlank()
                || "unknown".equals(request.modelVersion())) {
            throw new IllegalArgumentException("实验必须绑定明确的 modelVersion");
        }
        return experimentRunner.run(dataset, baseline, candidate,
                request.indexVersion(), request.modelVersion(),
                request.randomSeed() == null ? 0 : request.randomSeed(),
                request.repetitions() == null ? 1 : request.repetitions());
    }

    /** 创建策略请求体。 */
    public record CreatePolicyRequest(
            String policyId,
            String version,
            Map<String, String> selectorRules,
            Map<String, Double> rankingWeights,
            Map<String, Integer> thresholds,
            Map<String, Boolean> featureFlags,
            String parentVersion
    ) {
    }

    /** 运行实验请求体。 */
    public record RunExperimentRequest(
            String datasetVersion,
            String baselinePolicyId,
            String baselinePolicyVersion,
            String candidatePolicyId,
            String candidatePolicyVersion,
            String indexVersion,
            String modelVersion,
            Long randomSeed,
            Integer repetitions
    ) {
    }

    /** 候选审核请求体：action 为 submit/approve/reject，relevantIds/queryPreview 可人工修正。 */
    public record ReviewCandidateRequest(
            String action,
            List<String> relevantIds,
            String queryPreview
    ) {
    }
}
