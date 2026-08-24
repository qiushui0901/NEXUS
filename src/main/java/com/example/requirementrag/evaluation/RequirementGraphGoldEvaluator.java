package com.example.requirementrag.evaluation;

import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldClaim;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCodeFact;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldDecision;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldEntity;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldEvidenceItem;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldRelation;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldUncertainty;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldEvalReport;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictedRelation;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.Prediction;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.ScenarioMetrics;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * 需求语义图金标评测器：按场景聚合实体/关系/Claim/存疑/代码事实指标。
 *
 * <p>从代码 Review 修订：
 * <ul>
 *   <li>实体/关系/Claim/代码事实一律使用<b>最大一对一匹配</b>，杜绝重复预测虚高 Precision；</li>
 *   <li>Claim 改为 {@code factKey && value} 严格匹配，值用归一化器处理（如 30 秒 / 30s / 30）；</li>
 *   <li>代码事实要求 repository/commit/factKey/value 全匹配，新增 codeFactPrecision/F1；</li>
 *   <li>漂移/发布决策直接对照 Gold 显式 {@code decision} 逐字段比较，不再从 scenario 硬编码；</li>
 *   <li>evidence 增加 sourceMatch / offsetValidity / claimSupport 三项实际回查指标。</li>
 * </ul>
 *
 * <p>RETRIEVAL_TEST_CASE 不进入抽取 F1，单独计列。
 * 负例场景（DOUBT_NEGATIVE / OPEN_DOUBT_NO_DRIFT / DOCUMENT_CONFLICT）只统计负例错误率。
 */
@Component
public class RequirementGraphGoldEvaluator {

    private static final String RETRIEVAL = "RETRIEVAL_TEST_CASE";
    private static final Set<String> NEGATIVE_SCENARIOS = Set.of(
            "DOUBT_NEGATIVE", "OPEN_DOUBT_NO_DRIFT", "DOCUMENT_CONFLICT");
    /** 单条预测最大等待时间，防止一个 LLM 请求卡死整个评测。 */
    private static final long PER_CALL_TIMEOUT_MS = 120_000L;

    private static final Prediction EMPTY_PREDICTION =
            new Prediction(Set.of(), List.of(), List.of(), List.of());

    public GoldEvalReport evaluate(List<GoldCase> cases, RequirementGraphGoldPredictor predictor) {
        return evaluateWith(cases, predictor::predict);
    }

    /** 并行预测（LLM 场景显著提速）：固定线程池逐条预测后聚合指标。 */
    public GoldEvalReport evaluateParallel(List<GoldCase> cases, RequirementGraphGoldPredictor predictor,
                                           int parallelism) {
        return evaluateParallel(cases, predictor, parallelism, PER_CALL_TIMEOUT_MS);
    }

    /** 并行预测；{@code perCallTimeoutMs} 允许按预测器成本调整（完整 BuildService 链路单条可能超过 120s）。 */
    public GoldEvalReport evaluateParallel(List<GoldCase> cases, RequirementGraphGoldPredictor predictor,
                                           int parallelism, long perCallTimeoutMs) {
        int poolSize = Math.max(1, Math.min(parallelism, cases.size()));
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        List<Future<CasePrediction>> futures = new ArrayList<>();
        try {
            for (GoldCase goldCase : cases) {
                futures.add(executor.submit(() -> new CasePrediction(goldCase.caseId(), predictor.predict(goldCase))));
            }
            Map<String, Prediction> predictions = new ConcurrentHashMap<>();
            for (int index = 0; index < futures.size(); index++) {
                Future<CasePrediction> future = futures.get(index);
                String caseId = cases.get(index).caseId();
                try {
                    CasePrediction completed = future.get(perCallTimeoutMs, TimeUnit.MILLISECONDS);
                    predictions.put(completed.caseId(), completed.prediction());
                } catch (TimeoutException exception) {
                    future.cancel(true);
                    // 单条超时只影响该用例：记录为 MODEL_TIMEOUT，继续完成其余用例。
                    predictions.put(caseId, timeoutPrediction(perCallTimeoutMs));
                } catch (ExecutionException exception) {
                    // ExecutionException 只是包装任务异常，不设置中断标记；该用例记录失败并继续。
                    predictions.put(caseId, failedPrediction(exception.getCause()));
                }
            }
            return evaluateWith(cases, goldCase -> predictions.getOrDefault(goldCase.caseId(), EMPTY_PREDICTION));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并行金标预测被中断", exception);
        } finally {
            executor.shutdownNow();
        }
    }

    private Prediction timeoutPrediction(long timeoutMs) {
        return new Prediction(Set.of(), List.of(), List.of(), List.of(), List.of(),
                new RequirementGraphGoldModels.DriftDecision("", "", "", List.of()),
                RequirementGraphGoldModels.PublicationDecision.NOT_PUBLISHED,
                RequirementGraphGoldModels.PredictionStatus.MODEL_TIMEOUT, "MODEL_TIMEOUT", timeoutMs, 0);
    }

    private Prediction failedPrediction(Throwable cause) {
        return new Prediction(Set.of(), List.of(), List.of(), List.of(), List.of(),
                new RequirementGraphGoldModels.DriftDecision("", "", "", List.of()),
                RequirementGraphGoldModels.PublicationDecision.NOT_PUBLISHED,
                RequirementGraphGoldModels.PredictionStatus.FAILURE, "PREDICTION_EXCEPTION", 0, 0);
    }

    private record CasePrediction(String caseId, Prediction prediction) {
    }

    private GoldEvalReport evaluateWith(List<GoldCase> cases, Function<GoldCase, Prediction> predictFn) {
        Map<String, Prediction> predictions = new LinkedHashMap<>();
        for (GoldCase goldCase : cases) {
            predictions.put(goldCase.caseId(), predictFn.apply(goldCase));
        }

        Map<RequirementGraphGoldModels.PredictionStatus, Integer> statusCounts = new LinkedHashMap<>();
        Map<String, Integer> errorCodeCounts = new LinkedHashMap<>();
        long totalLatencyMs = 0;
        for (Prediction prediction : predictions.values()) {
            statusCounts.merge(prediction.status(), 1, Integer::sum);
            if (prediction.errorCode() != null && !prediction.errorCode().isBlank()) {
                errorCodeCounts.merge(prediction.errorCode(), 1, Integer::sum);
            }
            totalLatencyMs += prediction.latencyMs();
        }

        Map<String, Accumulator> byScenario = new LinkedHashMap<>();
        int total = 0;
        int extraction = 0;
        int retrieval = 0;
        for (GoldCase goldCase : cases) {
            total++;
            if (RETRIEVAL.equals(goldCase.scenario())) {
                retrieval++;
                continue;
            }
            extraction++;
            Accumulator accumulator = byScenario.computeIfAbsent(goldCase.scenario(),
                    ignored -> new Accumulator(goldCase.scenario()));
            accumulate(goldCase, predictions.get(goldCase.caseId()), accumulator);
        }

        List<ScenarioMetrics> scenarios = new ArrayList<>();
        for (Accumulator accumulator : byScenario.values()) {
            scenarios.add(accumulator.metrics());
        }
        Accumulator overall = new Accumulator("OVERALL");
        Accumulator overallSuccessOnly = new Accumulator("OVERALL_SUCCESS_ONLY");
        FailedCaseStats failed = new FailedCaseStats();
        for (GoldCase goldCase : cases) {
            if (RETRIEVAL.equals(goldCase.scenario())) continue;
            Prediction prediction = predictions.get(goldCase.caseId());
            accumulate(goldCase, prediction, overall);
            if (prediction != null && prediction.status() == RequirementGraphGoldModels.PredictionStatus.SUCCESS) {
                accumulate(goldCase, prediction, overallSuccessOnly);
            } else if (prediction != null) {
                failed.cases++;
                failed.goldEntityTotal += goldCase.entities().size();
                failed.goldEntityMatched += countGoldEntityMatches(goldCase, prediction);
            }
        }
        EvidenceMetrics evidence = computeEvidenceMetrics(cases);
        double completeness = evidence.totalItems() == 0 ? 1.0
                : (double) evidence.fieldComplete() / evidence.totalItems();
        long averageLatencyMs = predictions.isEmpty() ? 0 : totalLatencyMs / predictions.size();
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("negativeScenarios", NEGATIVE_SCENARIOS);
        extras.put("retrievalExcludedFromExtraction", true);
        extras.put("predictionStatusCounts", statusCounts);
        extras.put("predictionErrorCodeCounts", errorCodeCounts);
        extras.put("totalLatencyMs", totalLatencyMs);
        extras.put("averageLatencyMs", averageLatencyMs);
        extras.put("matchingMode", "ONE_TO_ONE");
        extras.put("claimMatching", "KEY_AND_VALUE");
        extras.put("goldEvidenceFieldCompletenessRate", completeness);
        extras.put("goldEvidenceSourceMatchRate",
                evidence.totalItems() == 0 ? 1.0 : (double) evidence.sourceMatched() / evidence.totalItems());
        extras.put("goldEvidenceOffsetValidityRate",
                evidence.offsetValidTotal() == 0 ? Double.NaN : (double) evidence.offsetValidOk() / evidence.offsetValidTotal());
        extras.put("goldEvidenceClaimSupportRate",
                evidence.totalClaims() == 0 ? 1.0 : (double) evidence.claimSupported() / evidence.totalClaims());
        extras.put("goldEvidenceCounts", Map.of(
                "totalItems", evidence.totalItems(),
                "fieldComplete", evidence.fieldComplete(),
                "sourceMatched", evidence.sourceMatched(),
                "offsetValidTotal", evidence.offsetValidTotal(),
                "offsetValidOk", evidence.offsetValidOk(),
                "totalClaims", evidence.totalClaims(),
                "claimSupported", evidence.claimSupported()));
        // —— 正式关系本体约束：只有谓词属于生产 RelationType 的关系进入“本体对齐关系 F1”，
        // 非本体谓词（业务属性/边界约束/实现状态）单独计数，避免把领域谓词误当成生产图谱能力。
        OntologyRelationMetrics ontology = computeOntologyRelationMetrics(cases, predictions);
        extras.put("ontologyAlignedRelationCount", ontology.ontologyGoldCount());
        extras.put("nonOntologyGoldRelationCount", ontology.nonOntologyGoldCount());
        extras.put("boundaryConstraintGoldRelationCount", ontology.boundaryGoldCount());
        extras.put("ontologyAlignedRelationPrecision", ontology.precision());
        extras.put("ontologyAlignedRelationRecall", ontology.recall());
        extras.put("ontologyAlignedRelationF1", ontology.f1());
        // —— 严格口径 vs 仅成功样本口径 + 失败率/失败召回损失 ——
        ScenarioMetrics strictOverall = overall.metrics();
        ScenarioMetrics successOnly = overallSuccessOnly.metrics();
        extras.put("strictOverallEntityF1", strictOverall.entityF1());
        extras.put("strictOverallRelationF1", strictOverall.relationF1());
        extras.put("strictOverallClaimF1", strictOverall.claimF1());
        extras.put("strictOverallCodeFactF1", strictOverall.codeFactF1());
        extras.put("successfulOnlyOverallEntityF1", successOnly.entityF1());
        extras.put("successfulOnlyOverallRelationF1", successOnly.relationF1());
        extras.put("successfulOnlyOverallClaimF1", successOnly.claimF1());
        extras.put("successfulOnlyOverallCodeFactF1", successOnly.codeFactF1());
        int successCount = statusCounts.getOrDefault(RequirementGraphGoldModels.PredictionStatus.SUCCESS, 0);
        int totalPredictions = predictions.size();
        extras.put("predictionSuccessRate",
                totalPredictions == 0 ? Double.NaN : (double) successCount / totalPredictions);
        extras.put("failedCaseCount", failed.cases);
        extras.put("partialFailureRate",
                totalPredictions == 0 ? Double.NaN : (double) failed.cases / totalPredictions);
        extras.put("failedCaseEntityRecall",
                failed.goldEntityTotal == 0 ? Double.NaN
                        : (double) failed.goldEntityMatched / failed.goldEntityTotal);
        return new GoldEvalReport(total, extraction, retrieval, List.copyOf(scenarios),
                strictOverall, completeness, extras);
    }

    private void accumulate(GoldCase goldCase, Prediction prediction, Accumulator accumulator) {
        accumulator.cases++;
        Set<RequirementGraphGoldModels.PredictedEntity> predictedEntities =
                prediction.entities() == null ? Set.of() : prediction.entities();
        List<GoldEntity> goldEntities = goldCase.entities() == null ? List.of() : goldCase.entities();
        int entityMatches = matchEntities(predictedEntities, goldEntities);
        accumulator.entityTp += entityMatches;
        accumulator.entityFp += Math.max(0, predictedEntities.size() - entityMatches);
        accumulator.entityFn += Math.max(0, goldEntities.size() - entityMatches);

        List<PredictedRelation> predictedRelations = prediction.relations() == null ? List.of() : prediction.relations();
        List<GoldRelation> goldRelations = goldCase.relations() == null ? List.of() : goldCase.relations();
        Map<String, String> idToName = entityIdToName(goldEntities);
        int relationMatches = matchRelations(predictedRelations, goldRelations, idToName);
        accumulator.relationTp += relationMatches;
        accumulator.relationFp += Math.max(0, predictedRelations.size() - relationMatches);
        accumulator.relationFn += Math.max(0, goldRelations.size() - relationMatches);

        List<RequirementGraphGoldModels.PredictedClaim> predictedClaims =
                prediction.claims() == null ? List.of() : prediction.claims();
        List<GoldClaim> goldClaims = goldCase.claims() == null ? List.of() : goldCase.claims();
        int claimMatches = matchClaims(predictedClaims, goldClaims);
        accumulator.claimTp += claimMatches;
        accumulator.claimFp += Math.max(0, predictedClaims.size() - claimMatches);
        accumulator.claimFn += Math.max(0, goldClaims.size() - claimMatches);

        List<String> predictedUncertainties = prediction.uncertainties() == null ? List.of() : prediction.uncertainties();
        List<GoldUncertainty> goldUncertainties = goldCase.uncertainties() == null ? List.of() : goldCase.uncertainties();
        for (GoldUncertainty gold : goldUncertainties) {
            if (matchesAnyUncertainty(gold.question(), predictedUncertainties)) accumulator.uncertaintyTp++;
            else accumulator.uncertaintyFn++;
        }

        if (NEGATIVE_SCENARIOS.contains(goldCase.scenario())) {
            accumulator.negativeCases++;
            boolean hasConfirmed = (prediction.relations() != null && !prediction.relations().isEmpty())
                    || (prediction.claims() != null && !prediction.claims().isEmpty());
            boolean published = prediction.publicationDecision() == RequirementGraphGoldModels.PublicationDecision.PUBLISH;
            boolean error = switch (goldCase.scenario()) {
                case "DOUBT_NEGATIVE" -> hasConfirmed;
                case "OPEN_DOUBT_NO_DRIFT", "DOCUMENT_CONFLICT" -> published;
                default -> false;
            };
            if (error) {
                accumulator.negativeErrors++;
            }
        }

        List<GoldCodeFact> goldCodeFacts = goldCase.codeFacts() == null ? List.of() : goldCase.codeFacts();
        List<RequirementGraphGoldModels.PredictedCodeFact> predictedCodeFacts = prediction.codeFacts() == null
                ? List.of() : prediction.codeFacts();
        int codeMatches = matchCodeFacts(predictedCodeFacts, goldCodeFacts);
        accumulator.codeTp += codeMatches;
        accumulator.codeFp += Math.max(0, predictedCodeFacts.size() - codeMatches);
        accumulator.codeFn += Math.max(0, goldCodeFacts.size() - codeMatches);

        GoldDecision decision = goldCase.decision();
        if (decision != null && !decision.type().isBlank()) {
            accumulator.driftCases++;
            if (isDriftCorrect(goldCase, prediction)) {
                accumulator.driftCorrect++;
            }
        }
    }

    /** 逐字段比较期望决策：type/status/publication 精确 + evidence 覆盖。 */
    private boolean isDriftCorrect(GoldCase goldCase, Prediction prediction) {
        GoldDecision gold = goldCase.decision();
        if (gold == null) return false;
        String goldType = normalize(gold.type());
        String goldStatus = normalize(gold.status());
        String goldPublication = normalize(gold.publication());
        RequirementGraphGoldModels.DriftDecision predictedDrift = prediction.driftDecision();
        String predType = predictedDrift == null ? "" : normalize(predictedDrift.type());
        String predStatus = predictedDrift == null ? "" : normalize(predictedDrift.status());
        String predPublication = prediction.publicationDecision() == null
                ? "" : normalize(prediction.publicationDecision().name());
        boolean typeOk = goldType.isBlank() || predType.equals(goldType);
        boolean statusOk = goldStatus.isBlank() || predStatus.equals(goldStatus);
        boolean publicationOk = goldPublication.isBlank() || predPublication.equals(goldPublication);
        boolean evidenceOk = true;
        if (gold.evidenceIds() != null && !gold.evidenceIds().isEmpty()) {
            Set<String> predEvidence = new HashSet<>(
                    predictedDrift == null || predictedDrift.evidenceIds() == null
                            ? List.of() : predictedDrift.evidenceIds());
            evidenceOk = predEvidence.containsAll(gold.evidenceIds());
        }
        return typeOk && statusOk && publicationOk && evidenceOk;
    }

    private int matchEntities(Set<RequirementGraphGoldModels.PredictedEntity> predicted, List<GoldEntity> gold) {
        List<RequirementGraphGoldModels.PredictedEntity> preds = predicted == null ? List.of()
                : predicted.stream().filter(entity -> nonBlank(entity.canonicalName())).toList();
        return maxBipartiteMatches(gold.size(), preds.size(), (gIdx, pIdx) ->
                entityMatches(preds.get(pIdx), gold.get(gIdx)));
    }

    /** 实体匹配：名称/别名一致，且预测类型非空时必须与 Gold 类型一致（预测类型为空则视为未提供类型，不做类型校验）。 */
    private boolean entityMatches(RequirementGraphGoldModels.PredictedEntity predicted, GoldEntity gold) {
        if (blank(predicted.canonicalName())) return false;
        String norm = normalize(predicted.canonicalName());
        if (norm.equals(normalize(gold.canonicalName()))) {
            return typeMatches(predicted.type(), gold.type());
        }
        for (String alias : gold.aliases()) {
            if (norm.equals(normalize(alias))) {
                return typeMatches(predicted.type(), gold.type());
            }
        }
        return false;
    }

    private boolean typeMatches(String predictedType, String goldType) {
        if (blank(predictedType)) return true; // 未携带类型：无法验证，放行（由带类型的生产链路评测覆盖类型维度）
        return normalize(predictedType).equals(normalize(goldType));
    }

    private int matchRelations(List<PredictedRelation> predicted, List<GoldRelation> gold,
                               Map<String, String> idToName) {
        List<PredictedRelation> preds = predicted == null ? List.of() : predicted;
        return maxBipartiteMatches(gold.size(), preds.size(), (gIdx, pIdx) ->
                relationMatches(preds.get(pIdx), gold.get(gIdx), idToName));
    }

    private boolean relationMatches(PredictedRelation predicted, GoldRelation gold, Map<String, String> idToName) {
        if (!normalize(predicted.predicate()).equals(normalize(gold.predicate()))) return false;
        String goldSource = resolveName(gold.subject(), idToName);
        String goldTarget = resolveName(gold.object(), idToName);
        return normalize(predicted.source()).equals(normalize(goldSource))
                && normalize(predicted.target()).equals(normalize(goldTarget));
    }

    private int matchClaims(List<RequirementGraphGoldModels.PredictedClaim> predicted, List<GoldClaim> gold) {
        List<RequirementGraphGoldModels.PredictedClaim> preds = predicted == null ? List.of() : predicted;
        return maxBipartiteMatches(gold.size(), preds.size(), (gIdx, pIdx) ->
                claimMatches(preds.get(pIdx), gold.get(gIdx)));
    }

    /** Claim 严格匹配：factKey 精确 && value 归一化等价。 */
    private boolean claimMatches(RequirementGraphGoldModels.PredictedClaim predicted, GoldClaim gold) {
        if (blank(predicted.factKey()) || blank(gold.factKey())) return false;
        if (!normalize(predicted.factKey()).equals(normalize(gold.factKey()))) return false;
        return !blank(predicted.value()) && !blank(gold.value())
                && normalizeValue(predicted.value()).equals(normalizeValue(gold.value()));
    }

    private int matchCodeFacts(List<RequirementGraphGoldModels.PredictedCodeFact> predicted, List<GoldCodeFact> gold) {
        List<RequirementGraphGoldModels.PredictedCodeFact> preds = predicted == null ? List.of() : predicted;
        return maxBipartiteMatches(gold.size(), preds.size(), (gIdx, pIdx) ->
                codeFactMatches(preds.get(pIdx), gold.get(gIdx)));
    }

    /** 代码事实精确匹配：repository/commit/factKey/value 全部一致。 */
    private boolean codeFactMatches(RequirementGraphGoldModels.PredictedCodeFact predicted, GoldCodeFact gold) {
        if (!normalize(predicted.factKey()).equals(normalize(gold.factKey()))) return false;
        if (!normalizeValue(predicted.value()).equals(normalizeValue(gold.value()))) return false;
        if (!blank(gold.repositoryId()) && !normalize(predicted.repositoryId()).equals(normalize(gold.repositoryId()))) {
            return false;
        }
        if (!blank(gold.commitSha()) && !normalize(predicted.commitSha()).equals(normalize(gold.commitSha()))) {
            return false;
        }
        return true;
    }

    /**
     * 最大一对一匹配（Kuhn 算法）：每条 Gold 与每条 Prediction 最多匹配一次。
     * 返回可同时成立的匹配对数量，作为 TP。
     */
    private int maxBipartiteMatches(int leftCount, int rightCount, BiPredicate<Integer, Integer> edge) {
        int[] matchRight = new int[rightCount];
        Arrays.fill(matchRight, -1);
        int matches = 0;
        for (int left = 0; left < leftCount; left++) {
            boolean[] seen = new boolean[rightCount];
            if (tryKuhn(left, seen, matchRight, edge)) matches++;
        }
        return matches;
    }

    private boolean tryKuhn(int left, boolean[] seen, int[] matchRight, BiPredicate<Integer, Integer> edge) {
        for (int right = 0; right < matchRight.length; right++) {
            if (seen[right] || !edge.test(left, right)) continue;
            seen[right] = true;
            if (matchRight[right] == -1 || tryKuhn(matchRight[right], seen, matchRight, edge)) {
                matchRight[right] = left;
                return true;
            }
        }
        return false;
    }

    private int countGoldEntityMatches(GoldCase goldCase, Prediction prediction) {
        Set<RequirementGraphGoldModels.PredictedEntity> predicted =
                prediction.entities() == null ? Set.of() : prediction.entities();
        return matchEntities(predicted, goldCase.entities());
    }

    private boolean matchesAnyUncertainty(String goldQuestion, List<String> predictedUncertainties) {
        String gold = normalize(goldQuestion);
        if (gold.isBlank()) return false;
        for (String predicted : predictedUncertainties) {
            String pred = normalize(predicted);
            if (gold.contains(pred) || pred.contains(gold)) return true;
        }
        return false;
    }

    private Map<String, String> entityIdToName(List<GoldEntity> entities) {
        Map<String, String> map = new LinkedHashMap<>();
        for (GoldEntity entity : entities) {
            map.putIfAbsent(entity.id(), entity.canonicalName());
        }
        return map;
    }

    private String resolveName(String id, Map<String, String> idToName) {
        String name = idToName.get(id);
        return name == null ? id : name;
    }

    /** 证据实际回查：字段完整率 / 来源匹配率 / offset 有效性 / claim 支持率。 */
    private EvidenceMetrics computeEvidenceMetrics(List<GoldCase> cases) {
        Map<String, Boolean> evidenceSupported = new HashMap<>();
        for (GoldCase goldCase : cases) {
            for (GoldEvidenceItem item : goldCase.evidenceItems()) {
                boolean matched = sourceMatches(item);
                evidenceSupported.put(item.evidenceId(), matched);
            }
        }
        int totalItems = 0;
        int fieldComplete = 0;
        int sourceMatched = 0;
        int offsetValidTotal = 0;
        int offsetValidOk = 0;
        int totalClaims = 0;
        int claimSupported = 0;
        for (GoldCase goldCase : cases) {
            for (GoldEvidenceItem item : goldCase.evidenceItems()) {
                totalItems++;
                if (!blank(item.sourceFile()) && !blank(item.quote())) fieldComplete++;
                boolean matched = evidenceSupported.getOrDefault(item.evidenceId(), false);
                if (matched) sourceMatched++;
                if (item.hasOffset()) {
                    offsetValidTotal++;
                    if (offsetValid(item)) offsetValidOk++;
                }
            }
            for (GoldClaim claim : goldCase.claims()) {
                totalClaims++;
                boolean supported = false;
                for (String evidenceId : claim.evidenceIds()) {
                    if (Boolean.TRUE.equals(evidenceSupported.get(evidenceId))) {
                        supported = true;
                        break;
                    }
                }
                if (supported) claimSupported++;
            }
        }
        return new EvidenceMetrics(totalItems, fieldComplete, sourceMatched,
                offsetValidTotal, offsetValidOk, totalClaims, claimSupported);
    }

    private boolean sourceMatches(GoldEvidenceItem item) {
        if (blank(item.sourceFile()) || blank(item.quote())) return false;
        String content = readSource(item.sourceFile());
        if (content == null) return false;
        return content.contains(item.quote().trim());
    }

    private boolean offsetValid(GoldEvidenceItem item) {
        if (!item.hasOffset() || item.startOffset() < 0) return false;
        String content = readSource(item.sourceFile());
        if (content == null) return false;
        int start = item.startOffset();
        if (start > content.length()) return false;
        String quote = item.quote().trim();
        if (quote.isEmpty()) return false;
        if (!content.startsWith(quote, start)) return false;
        return item.endOffset() < 0 || item.endOffset() >= start + quote.length();
    }

    private final Map<String, String> sourceCache = new ConcurrentHashMap<>();
    private final Set<String> missingSources = ConcurrentHashMap.newKeySet();

    private String readSource(String sourceFile) {
        if (missingSources.contains(sourceFile)) return null;
        return sourceCache.computeIfAbsent(sourceFile, file -> {
            try {
                Path path = Path.of(file);
                if (Files.exists(path)) {
                    return Files.readString(path, StandardCharsets.UTF_8);
                }
                // 兼容“带 sheet 的 xlsx 引用”，按 `#` 前的路径解析。
                int hash = file.indexOf('#');
                if (hash > 0) {
                    Path base = Path.of(file.substring(0, hash));
                    if (Files.exists(base)) return Files.readString(base, StandardCharsets.UTF_8);
                }
                missingSources.add(file);
                return null;
            } catch (IOException exception) {
                missingSources.add(file);
                return null;
            }
        });
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s|｜:：（）()\\[\\]【】、，,。.;；/\\\\_\\-]+", "");
    }

    /** 值归一化：统一单位别名（秒/s、分钟/min、小时/h、天/d），再做通用归一化。 */
    private String normalizeValue(String value) {
        if (value == null) return "";
        String normalized = value.toLowerCase(Locale.ROOT)
                .replace("秒", "s")
                .replace("分钟", "min")
                .replace("小时", "h")
                .replace("天", "d");
        return normalize(normalized);
    }

    private record EvidenceMetrics(int totalItems, int fieldComplete, int sourceMatched,
                                   int offsetValidTotal, int offsetValidOk, int totalClaims, int claimSupported) {
    }

    private OntologyRelationMetrics computeOntologyRelationMetrics(List<GoldCase> cases,
                                                                   Map<String, Prediction> predictions) {
        int ontologyGold = 0;
        int nonOntologyGold = 0;
        int boundaryGold = 0;
        int tp = 0;
        int fp = 0;
        int fn = 0;
        for (GoldCase goldCase : cases) {
            if (RETRIEVAL.equals(goldCase.scenario())) continue;
            List<GoldRelation> goldRelations = goldCase.relations() == null ? List.of() : goldCase.relations();
            Prediction prediction = predictions.get(goldCase.caseId());
            List<PredictedRelation> predictedRelations = prediction == null || prediction.relations() == null
                    ? List.of() : prediction.relations();
            List<GoldRelation> goldOntology = new ArrayList<>();
            List<PredictedRelation> predOntology = new ArrayList<>();
            for (GoldRelation relation : goldRelations) {
                if (RelationOntologyMapper.toProductionType(relation.predicate()) != null) {
                    goldOntology.add(relation);
                    ontologyGold++;
                } else {
                    nonOntologyGold++;
                    String predicate = relation.predicate() == null ? "" : relation.predicate().toUpperCase(Locale.ROOT);
                    if (predicate.startsWith("MUST_NOT_")) boundaryGold++;
                }
            }
            for (PredictedRelation relation : predictedRelations) {
                if (RelationOntologyMapper.toProductionType(relation.predicate()) != null) predOntology.add(relation);
            }
            Map<String, String> idToName = entityIdToName(goldCase.entities());
            int matches = matchOntologyRelations(predOntology, goldOntology, idToName);
            tp += matches;
            fp += Math.max(0, predOntology.size() - matches);
            fn += Math.max(0, goldOntology.size() - matches);
        }
        double precision = ratio(tp, tp + fp);
        double recall = ratio(tp, tp + fn);
        return new OntologyRelationMetrics(ontologyGold, nonOntologyGold, boundaryGold,
                precision, recall, f1(precision, recall));
    }

    /** 本体对齐关系匹配：两端谓词都映射到生产 RelationType，且映射后类型一致。 */
    private int matchOntologyRelations(List<PredictedRelation> predicted, List<GoldRelation> gold,
                                       Map<String, String> idToName) {
        return maxBipartiteMatches(gold.size(), predicted.size(), (gIdx, pIdx) -> {
            String goldType = RelationOntologyMapper.toProductionType(gold.get(gIdx).predicate());
            String predType = RelationOntologyMapper.toProductionType(predicted.get(pIdx).predicate());
            if (goldType == null || predType == null || !goldType.equals(predType)) return false;
            String goldSource = resolveName(gold.get(gIdx).subject(), idToName);
            String goldTarget = resolveName(gold.get(gIdx).object(), idToName);
            return normalize(predicted.get(pIdx).source()).equals(normalize(goldSource))
                    && normalize(predicted.get(pIdx).target()).equals(normalize(goldTarget));
        });
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static double f1(double precision, double recall) {
        return precision + recall == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
    }

    private record OntologyRelationMetrics(int ontologyGoldCount, int nonOntologyGoldCount, int boundaryGoldCount,
                                           double precision, double recall, double f1) {
    }

    /** 失败/部分失败用例的 Gold 覆盖统计（用于 failedCaseEntityRecall）。 */
    private static final class FailedCaseStats {
        private int cases;
        private int goldEntityTotal;
        private int goldEntityMatched;
    }

    /** 按场景聚合计数器。 */
    private static final class Accumulator {
        private final String scenario;
        private int entityTp;
        private int entityFp;
        private int entityFn;
        private int relationTp;
        private int relationFp;
        private int relationFn;
        private int claimTp;
        private int claimFp;
        private int claimFn;
        private int uncertaintyTp;
        private int uncertaintyFn;
        private int codeTp;
        private int codeFp;
        private int codeFn;
        private int negativeCases;
        private int negativeErrors;
        private int driftCases;
        private int driftCorrect;
        private int cases;

        Accumulator(String scenario) {
            this.scenario = scenario;
        }

        ScenarioMetrics metrics() {
            double entityPrecision = ratio(entityTp, entityTp + entityFp);
            double entityRecall = ratio(entityTp, entityTp + entityFn);
            double relationPrecision = ratio(relationTp, relationTp + relationFp);
            double relationRecall = ratio(relationTp, relationTp + relationFn);
            double claimPrecision = ratio(claimTp, claimTp + claimFp);
            double claimRecall = ratio(claimTp, claimTp + claimFn);
            double uncertaintyRecall = ratio(uncertaintyTp, uncertaintyTp + uncertaintyFn);
            double codeFactRecall = ratio(codeTp, codeTp + codeFn);
            double codeFactPrecision = ratio(codeTp, codeTp + codeFp);
            double negativeErrorRate = negativeCases == 0 ? Double.NaN : (double) negativeErrors / negativeCases;
            double driftAccuracy = driftCases == 0 ? Double.NaN : (double) driftCorrect / driftCases;
            return new ScenarioMetrics(scenario, cases,
                    entityPrecision, entityRecall, f1(entityPrecision, entityRecall),
                    relationPrecision, relationRecall, f1(relationPrecision, relationRecall),
                    claimPrecision, claimRecall, f1(claimPrecision, claimRecall),
                    negativeErrorRate, uncertaintyRecall, codeFactRecall, codeFactPrecision,
                    f1(codeFactPrecision, codeFactRecall), driftAccuracy);
        }

        private double ratio(int numerator, int denominator) {
            return denominator == 0 ? 0.0 : (double) numerator / denominator;
        }

        private double f1(double precision, double recall) {
            return precision + recall == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
        }
    }
}
