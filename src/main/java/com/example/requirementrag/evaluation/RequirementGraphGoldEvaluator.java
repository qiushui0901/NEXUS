package com.example.requirementrag.evaluation;

import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldClaim;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCodeFact;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldDecision;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldEntity;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldEvidenceItem;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldRelation;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldUncertainty;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldWindow;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldEvalReport;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictedRelation;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.Prediction;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.ScenarioMetrics;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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
        Accumulator overallAllOutput = new Accumulator("OVERALL_ALL_OUTPUT");
        Accumulator overallStrict = new Accumulator("OVERALL_STRICT");
        Accumulator overallSuccessOnly = new Accumulator("OVERALL_SUCCESS_ONLY");
        FailedCaseStats failed = new FailedCaseStats();
        EntityTypedStats entityTyped = new EntityTypedStats();
        UncertaintyPrecisionStats uncertaintyPrecision = new UncertaintyPrecisionStats();
        for (GoldCase goldCase : cases) {
            if (RETRIEVAL.equals(goldCase.scenario())) continue;
            Prediction prediction = predictions.get(goldCase.caseId());
            if (prediction == null) prediction = EMPTY_PREDICTION;
            // 1) allOutput：所有预测输出都计入（包括失败状态中的部分结果）
            accumulate(goldCase, prediction, overallAllOutput);
            // 2) strict：只要不是 SUCCESS，就按空结果计分（防止部分失败结果“补回”严格 F1）
            Prediction strictPrediction = prediction.status() == RequirementGraphGoldModels.PredictionStatus.SUCCESS
                    ? prediction : EMPTY_PREDICTION;
            accumulate(goldCase, strictPrediction, overallStrict);
            // 3) successfulOnly：只统计 SUCCESS 样本
            if (prediction.status() == RequirementGraphGoldModels.PredictionStatus.SUCCESS) {
                accumulate(goldCase, prediction, overallSuccessOnly);
            } else {
                failed.cases++;
                failed.goldEntityTotal += goldCase.entities().size();
                failed.goldEntityMatched += countGoldEntityMatches(goldCase, prediction);
            }
            collectEntityTypedStats(goldCase, prediction, entityTyped);
            collectUncertaintyPrecision(goldCase, prediction, uncertaintyPrecision);
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
        double quoteSourceMatchRate = evidence.totalItems() == 0 ? 1.0
                : (double) evidence.sourceMatched() / evidence.totalItems();
        extras.put("quoteSourceMatchRate", quoteSourceMatchRate);
        extras.put("goldEvidenceSourceMatchRate", quoteSourceMatchRate);
        extras.put("windowOffsetValidityRate",
                evidence.windowOffsetValidTotal() == 0 ? Double.NaN
                        : (double) evidence.windowOffsetValidOk() / evidence.windowOffsetValidTotal());
        extras.put("sourceFileOffsetValidityRate",
                evidence.sourceFileOffsetValidTotal() == 0 ? Double.NaN
                        : (double) evidence.sourceFileOffsetValidOk() / evidence.sourceFileOffsetValidTotal());
        int offsetValidTotal = evidence.windowOffsetValidTotal() + evidence.sourceFileOffsetValidTotal();
        int offsetValidOk = evidence.windowOffsetValidOk() + evidence.sourceFileOffsetValidOk();
        extras.put("goldEvidenceOffsetValidityRate",
                offsetValidTotal == 0 ? Double.NaN : (double) offsetValidOk / offsetValidTotal);
        extras.put("goldEvidenceClaimSupportRate",
                evidence.totalClaims() == 0 ? 1.0 : (double) evidence.claimSupported() / evidence.totalClaims());
        extras.put("goldEvidenceCounts", Map.ofEntries(
                Map.entry("totalItems", evidence.totalItems()),
                Map.entry("fieldComplete", evidence.fieldComplete()),
                Map.entry("sourceMatched", evidence.sourceMatched()),
                Map.entry("offsetValidTotal", offsetValidTotal),
                Map.entry("offsetValidOk", offsetValidOk),
                Map.entry("windowOffsetValidTotal", evidence.windowOffsetValidTotal()),
                Map.entry("windowOffsetValidOk", evidence.windowOffsetValidOk()),
                Map.entry("sourceFileOffsetValidTotal", evidence.sourceFileOffsetValidTotal()),
                Map.entry("sourceFileOffsetValidOk", evidence.sourceFileOffsetValidOk()),
                Map.entry("totalClaims", evidence.totalClaims()),
                Map.entry("claimSupported", evidence.claimSupported())));
        // —— 正式关系本体约束：只有谓词属于生产 RelationType 的关系进入“本体对齐关系 F1”，
        // 非本体谓词（业务属性/边界约束/实现状态）单独计数，避免把领域谓词误当成生产图谱能力。
        OntologyRelationMetrics ontology = computeOntologyRelationMetrics(cases, predictions);
        extras.put("ontologyAlignedRelationCount", ontology.ontologyGoldCount());
        extras.put("nonOntologyGoldRelationCount", ontology.nonOntologyGoldCount());
        extras.put("boundaryConstraintGoldRelationCount", ontology.boundaryGoldCount());
        extras.put("ontologyAlignedRelationPrecision", ontology.precision());
        extras.put("ontologyAlignedRelationRecall", ontology.recall());
        extras.put("ontologyAlignedRelationF1", ontology.f1());
        // —— 三套口径：allOutput / strict（非 SUCCESS 按空）/ successfulOnly + 失败率/失败召回损失 ——
        ScenarioMetrics allOutput = overallAllOutput.metrics();
        ScenarioMetrics strictOverall = overallStrict.metrics();
        ScenarioMetrics successOnly = overallSuccessOnly.metrics();
        extras.put("allOutputOverallEntityF1", allOutput.entityF1());
        extras.put("allOutputOverallRelationF1", allOutput.relationF1());
        extras.put("allOutputOverallClaimF1", allOutput.claimF1());
        extras.put("allOutputOverallCodeFactF1", allOutput.codeFactF1());
        extras.put("strictOverallEntityF1", strictOverall.entityF1());
        extras.put("strictOverallRelationF1", strictOverall.relationF1());
        extras.put("strictOverallClaimF1", strictOverall.claimF1());
        extras.put("strictOverallCodeFactF1", strictOverall.codeFactF1());
        extras.put("successfulOnlyOverallEntityF1", successOnly.entityF1());
        extras.put("successfulOnlyOverallRelationF1", successOnly.relationF1());
        extras.put("successfulOnlyOverallClaimF1", successOnly.claimF1());
        extras.put("successfulOnlyOverallCodeFactF1", successOnly.codeFactF1());
        // 实体类型口径：名称 F1 与带类型实体 F1 分开
        extras.put("entityNameF1", allOutput.entityF1());
        extras.put("entityTypedF1", entityTyped.f1());
        extras.put("entityTypedPrecision", entityTyped.precision());
        extras.put("entityTypedRecall", entityTyped.recall());
        extras.put("typedEntityRate", entityTyped.rate());
        extras.put("entityTypeAccuracy", entityTyped.typeAccuracy());
        // 存疑：修正子串匹配后附带 precision
        extras.put("uncertaintyRecall", allOutput.uncertaintyRecall());
        extras.put("uncertaintyPrecision", uncertaintyPrecision.precision());
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
        Map<String, GoldEntity> idToEntity = entityIdToEntity(goldEntities);
        int relationMatches = matchRelations(predictedRelations, goldRelations, idToName, idToEntity);
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
                               Map<String, String> idToName, Map<String, GoldEntity> idToEntity) {
        List<PredictedRelation> preds = predicted == null ? List.of() : predicted;
        return maxBipartiteMatches(gold.size(), preds.size(), (gIdx, pIdx) ->
                relationMatches(preds.get(pIdx), gold.get(gIdx), idToName, idToEntity));
    }

    private boolean relationMatches(PredictedRelation predicted, GoldRelation gold,
                                    Map<String, String> idToName, Map<String, GoldEntity> idToEntity) {
        if (!normalize(predicted.predicate()).equals(normalize(gold.predicate()))) return false;
        GoldEntity sourceEntity = idToEntity.get(gold.subject());
        GoldEntity targetEntity = idToEntity.get(gold.object());
        String goldSource = sourceEntity == null ? gold.subject() : sourceEntity.canonicalName();
        String goldTarget = targetEntity == null ? gold.object() : targetEntity.canonicalName();
        return endpointMatches(predicted.source(), goldSource, sourceEntity)
                && endpointMatches(predicted.target(), goldTarget, targetEntity);
    }

    /** 关系端点匹配复用实体匹配语义：canonicalName 或 gold alias 均可。 */
    private boolean endpointMatches(String predicted, String goldCanonical, GoldEntity entity) {
        String norm = normalize(predicted);
        if (norm.isBlank()) return false;
        if (norm.equals(normalize(goldCanonical))) return true;
        if (entity != null) {
            for (String alias : entity.aliases()) {
                if (norm.equals(normalize(alias))) return true;
            }
        }
        return false;
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
        if (gold.isBlank() || gold.length() < 4) return false;
        for (String predicted : predictedUncertainties) {
            if (predicted == null) continue;
            String pred = normalize(predicted);
            // 空字符串/过短子串不得命中，避免“什么都当存疑”虚高 recall。
            if (pred.isBlank() || pred.length() < 4) continue;
            if (gold.equals(pred)) return true;
            if (gold.contains(pred)) return true;
            if (pred.contains(gold)) return true;
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

    private Map<String, GoldEntity> entityIdToEntity(List<GoldEntity> entities) {
        Map<String, GoldEntity> map = new LinkedHashMap<>();
        for (GoldEntity entity : entities) {
            map.putIfAbsent(entity.id(), entity);
        }
        return map;
    }

    /** 证据实际回查：字段完整率 / quote 来源匹配率 / 窗口坐标系 offset / 源文件坐标系 offset / claim 支持率。 */
    private EvidenceMetrics computeEvidenceMetrics(List<GoldCase> cases) {
        Map<String, Boolean> evidenceSupported = new HashMap<>();
        for (GoldCase goldCase : cases) {
            for (GoldEvidenceItem item : goldCase.evidenceItems()) {
                boolean matched = sourceMatches(item);
                evidenceSupported.put(goldCase.caseId() + "|" + item.evidenceId(), matched);
            }
        }
        int totalItems = 0;
        int fieldComplete = 0;
        int sourceMatched = 0;
        int windowOffsetValidTotal = 0;
        int windowOffsetValidOk = 0;
        int sourceFileOffsetValidTotal = 0;
        int sourceFileOffsetValidOk = 0;
        int totalClaims = 0;
        int claimSupported = 0;
        for (GoldCase goldCase : cases) {
            for (GoldEvidenceItem item : goldCase.evidenceItems()) {
                totalItems++;
                if (!blank(item.sourceFile()) && !blank(item.quote())) fieldComplete++;
                boolean matched = evidenceSupported.getOrDefault(goldCase.caseId() + "|" + item.evidenceId(), false);
                if (matched) sourceMatched++;
                if (item.hasOffset()) {
                    GoldWindow window = findWindow(goldCase, item.windowId());
                    if (window != null) {
                        windowOffsetValidTotal++;
                        if (offsetValidInWindow(window, item)) windowOffsetValidOk++;
                    } else {
                        sourceFileOffsetValidTotal++;
                        if (offsetValidInSourceFile(item)) sourceFileOffsetValidOk++;
                    }
                }
            }
            for (GoldClaim claim : goldCase.claims()) {
                totalClaims++;
                boolean supported = false;
                for (String evidenceId : claim.evidenceIds()) {
                    if (Boolean.TRUE.equals(evidenceSupported.get(goldCase.caseId() + "|" + evidenceId))) {
                        supported = true;
                        break;
                    }
                }
                if (supported) claimSupported++;
            }
        }
        return new EvidenceMetrics(totalItems, fieldComplete, sourceMatched,
                windowOffsetValidTotal, windowOffsetValidOk,
                sourceFileOffsetValidTotal, sourceFileOffsetValidOk,
                totalClaims, claimSupported);
    }

    private GoldWindow findWindow(GoldCase goldCase, String windowId) {
        if (windowId == null || windowId.isBlank()) return null;
        for (GoldWindow window : goldCase.windows()) {
            if (windowId.equals(window.windowId())) return window;
        }
        return null;
    }

    private boolean sourceMatches(GoldEvidenceItem item) {
        if (blank(item.sourceFile()) || blank(item.quote())) return false;
        String content = readSource(item.sourceFile());
        if (content == null) return false;
        return content.contains(item.quote().trim());
    }

    /** 窗口坐标系 offset 校验：offset 是 GoldWindow.text 内的字符偏移。 */
    private boolean offsetValidInWindow(GoldWindow window, GoldEvidenceItem item) {
        if (!item.hasOffset() || item.startOffset() < 0) return false;
        String text = window.text();
        if (text == null) return false;
        int start = item.startOffset();
        if (start > text.length()) return false;
        String quote = item.quote().trim();
        if (quote.isEmpty()) return false;
        if (!text.startsWith(quote, start)) return false;
        return item.endOffset() < 0 || item.endOffset() >= start + quote.length();
    }

    /** 源文件坐标系 offset 校验：offset 是 sourceFile 全文的字符偏移。 */
    private boolean offsetValidInSourceFile(GoldEvidenceItem item) {
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
    private Path baseDirectory = Path.of("").toAbsolutePath().normalize();

    /** 设置数据集根目录，使相对 sourceFile 能按数据集位置解析（不依赖当前工作目录）。 */
    public void setBaseDirectory(Path baseDirectory) {
        this.baseDirectory = baseDirectory == null ? Path.of("").toAbsolutePath().normalize()
                : baseDirectory.toAbsolutePath().normalize();
    }

    private String readSource(String sourceFile) {
        if (missingSources.contains(sourceFile)) return null;
        return sourceCache.computeIfAbsent(sourceFile, file -> {
            try {
                String basePath = file;
                int hash = file.indexOf('#');
                if (hash > 0) basePath = file.substring(0, hash);
                Path path = Path.of(basePath).toAbsolutePath().normalize();
                if (!Files.exists(path) && !Path.of(basePath).isAbsolute()) {
                    Path resolved = baseDirectory.resolve(basePath).normalize();
                    if (Files.exists(resolved)) path = resolved;
                }
                if (!Files.exists(path)) {
                    missingSources.add(file);
                    return null;
                }
                String lower = basePath.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                    return readSpreadsheet(path);
                }
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException exception) {
                missingSources.add(file);
                return null;
            }
        });
    }

    /** 用 POI 提取 .xlsx/.xls 全部 sheet 的文本，使 quote 回查对 Excel 来源真实有效。 */
    private String readSpreadsheet(Path path) {
        try (Workbook workbook = WorkbookFactory.create(path.toFile())) {
            StringBuilder builder = new StringBuilder();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                builder.append("\n[sheet:").append(workbook.getSheetName(sheetIndex)).append("]\n");
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell == null) continue;
                        switch (cell.getCellType()) {
                            case STRING -> builder.append(cell.getStringCellValue()).append('\n');
                            case NUMERIC -> builder.append(cell.getNumericCellValue()).append('\n');
                            case BOOLEAN -> builder.append(cell.getBooleanCellValue()).append('\n');
                            case FORMULA -> builder.append(cell.getCellFormula()).append('\n');
                            default -> {
                            }
                        }
                    }
                }
            }
            return builder.toString();
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 Excel 来源: " + path, exception);
        }
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

    /** 实体类型口径：typedEntityRate / entityTypedPrecision/Recall/F1 / entityTypeAccuracy。 */
    private void collectEntityTypedStats(GoldCase goldCase, Prediction prediction, EntityTypedStats stats) {
        Set<RequirementGraphGoldModels.PredictedEntity> allPredicted =
                prediction.entities() == null ? Set.of() : prediction.entities();
        List<RequirementGraphGoldModels.PredictedEntity> typedPredicted = allPredicted.stream()
                .filter(entity -> !blank(entity.type()))
                .toList();
        List<GoldEntity> goldEntities = goldCase.entities() == null ? List.of() : goldCase.entities();
        int typedMatches = matchEntities(Set.copyOf(typedPredicted), goldEntities);
        stats.predictedTotal += allPredicted.size();
        stats.predictedTyped += typedPredicted.size();
        stats.typedTp += typedMatches;
        stats.typedFp += Math.max(0, typedPredicted.size() - typedMatches);
        stats.typedFn += Math.max(0, goldEntities.size() - typedMatches);
        for (RequirementGraphGoldModels.PredictedEntity predicted : typedPredicted) {
            GoldEntity matchedByName = findGoldEntityByName(predicted.canonicalName(), goldEntities);
            if (matchedByName != null) {
                stats.typeChecked++;
                if (typeMatches(predicted.type(), matchedByName.type())) {
                    stats.typeCorrect++;
                }
            }
        }
    }

    private GoldEntity findGoldEntityByName(String name, List<GoldEntity> goldEntities) {
        String norm = normalize(name);
        for (GoldEntity entity : goldEntities) {
            if (norm.equals(normalize(entity.canonicalName()))) return entity;
            for (String alias : entity.aliases()) {
                if (norm.equals(normalize(alias))) return entity;
            }
        }
        return null;
    }

    /** 存疑 precision：预测出的存疑有多少命中至少一条 Gold 存疑。 */
    private void collectUncertaintyPrecision(GoldCase goldCase, Prediction prediction, UncertaintyPrecisionStats stats) {
        List<String> predicted = prediction.uncertainties() == null ? List.of() : prediction.uncertainties();
        List<GoldUncertainty> goldUncertainties = goldCase.uncertainties() == null ? List.of() : goldCase.uncertainties();
        for (String item : predicted) {
            if (item == null || item.isBlank()) continue;
            stats.predictedTotal++;
            boolean matched = goldUncertainties.stream()
                    .anyMatch(gold -> matchesAnyUncertainty(gold.question(), List.of(item)));
            if (matched) stats.predictedMatched++;
        }
    }

    private static final class EntityTypedStats {
        private int predictedTotal;
        private int predictedTyped;
        private int typedTp;
        private int typedFp;
        private int typedFn;
        private int typeChecked;
        private int typeCorrect;

        private double rate() {
            return predictedTotal == 0 ? 0.0 : (double) predictedTyped / predictedTotal;
        }

        private double precision() {
            return typedTp + typedFp == 0 ? 0.0 : (double) typedTp / (typedTp + typedFp);
        }

        private double recall() {
            return typedTp + typedFn == 0 ? 0.0 : (double) typedTp / (typedTp + typedFn);
        }

        private double f1() {
            return RequirementGraphGoldEvaluator.f1(precision(), recall());
        }

        private double typeAccuracy() {
            return typeChecked == 0 ? Double.NaN : (double) typeCorrect / typeChecked;
        }
    }

    private static final class UncertaintyPrecisionStats {
        private int predictedTotal;
        private int predictedMatched;

        private double precision() {
            return predictedTotal == 0 ? Double.NaN : (double) predictedMatched / predictedTotal;
        }
    }

    private record EvidenceMetrics(int totalItems, int fieldComplete, int sourceMatched,
                                   int windowOffsetValidTotal, int windowOffsetValidOk,
                                   int sourceFileOffsetValidTotal, int sourceFileOffsetValidOk,
                                   int totalClaims, int claimSupported) {
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
            Map<String, GoldEntity> idToEntity = entityIdToEntity(goldCase.entities());
            int matches = matchOntologyRelations(predOntology, goldOntology, idToName, idToEntity);
            tp += matches;
            fp += Math.max(0, predOntology.size() - matches);
            fn += Math.max(0, goldOntology.size() - matches);
        }
        double precision = ratio(tp, tp + fp);
        double recall = ratio(tp, tp + fn);
        return new OntologyRelationMetrics(ontologyGold, nonOntologyGold, boundaryGold,
                precision, recall, f1(precision, recall));
    }

    /** 本体对齐关系匹配：两端谓词都映射到生产 RelationType，且映射后类型一致；端点支持 Gold alias。 */
    private int matchOntologyRelations(List<PredictedRelation> predicted, List<GoldRelation> gold,
                                       Map<String, String> idToName, Map<String, GoldEntity> idToEntity) {
        return maxBipartiteMatches(gold.size(), predicted.size(), (gIdx, pIdx) -> {
            String goldType = RelationOntologyMapper.toProductionType(gold.get(gIdx).predicate());
            String predType = RelationOntologyMapper.toProductionType(predicted.get(pIdx).predicate());
            if (goldType == null || predType == null || !goldType.equals(predType)) return false;
            return relationMatches(predicted.get(pIdx), gold.get(gIdx), idToName, idToEntity);
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
