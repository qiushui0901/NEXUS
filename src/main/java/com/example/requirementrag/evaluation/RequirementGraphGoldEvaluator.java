package com.example.requirementrag.evaluation;

import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldClaim;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldEntity;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldRelation;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldUncertainty;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldEvalReport;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictedRelation;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.Prediction;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.ScenarioMetrics;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
import java.util.function.Function;

/**
 * 需求语义图金标评测器：按场景聚合实体/关系/Claim/存疑/代码事实指标。
 *
 * <p>RETRIEVAL_TEST_CASE 不进入抽取 F1，单独计列。
 * 负例场景（DOUBT_NEGATIVE / OPEN_DOUBT_NO_DRIFT / DOCUMENT_CONFLICT）只统计负例错误率。
 */
@Component
public class RequirementGraphGoldEvaluator {

    private static final String RETRIEVAL = "RETRIEVAL_TEST_CASE";
    private static final Set<String> NEGATIVE_SCENARIOS = Set.of(
            "DOUBT_NEGATIVE", "OPEN_DOUBT_NO_DRIFT", "DOCUMENT_CONFLICT");
    private static final Set<String> DRIFT_SCENARIOS = Set.of(
            "DOCUMENT_DRIFT_REVIEW", "DOCUMENT_CONFLICT", "OPEN_DOUBT_NO_DRIFT", "NO_DRIFT_CODE_BOUNDARY");

    private static final Prediction EMPTY_PREDICTION =
            new Prediction(Set.of(), List.of(), List.of(), List.of());

    public GoldEvalReport evaluate(List<GoldCase> cases, RequirementGraphGoldPredictor predictor) {
        return evaluateWith(cases, predictor::predict);
    }

    /** 并行预测（LLM 场景显著提速）：固定线程池逐条预测后聚合指标。 */
    public GoldEvalReport evaluateParallel(List<GoldCase> cases, RequirementGraphGoldPredictor predictor,
                                           int parallelism) {
        int poolSize = Math.max(1, Math.min(parallelism, cases.size()));
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        try {
            Map<String, Prediction> predictions = new ConcurrentHashMap<>();
            List<Future<?>> futures = new ArrayList<>();
            for (GoldCase goldCase : cases) {
                futures.add(executor.submit(() ->
                        predictions.put(goldCase.caseId(), predictor.predict(goldCase))));
            }
            for (Future<?> future : futures) {
                future.get();
            }
            return evaluateWith(cases, goldCase -> predictions.getOrDefault(goldCase.caseId(), EMPTY_PREDICTION));
        } catch (InterruptedException | ExecutionException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并行金标预测失败", exception);
        } finally {
            executor.shutdownNow();
        }
    }

    private GoldEvalReport evaluateWith(List<GoldCase> cases, Function<GoldCase, Prediction> predictFn) {
        Map<String, Prediction> predictions = new LinkedHashMap<>();
        for (GoldCase goldCase : cases) {
            predictions.put(goldCase.caseId(), predictFn.apply(goldCase));
        }

        Map<RequirementGraphGoldModels.PredictionStatus, Integer> statusCounts = new LinkedHashMap<>();
        long totalLatencyMs = 0;
        for (Prediction prediction : predictions.values()) {
            statusCounts.merge(prediction.status(), 1, Integer::sum);
            totalLatencyMs += prediction.latencyMs();
        }

        Map<String, Accumulator> byScenario = new LinkedHashMap<>();
        int total = 0;
        int extraction = 0;
        int retrieval = 0;
        int totalEvidence = 0;
        int traceableEvidence = 0;
        for (GoldCase goldCase : cases) {
            total++;
            totalEvidence += goldCase.totalEvidenceItems();
            traceableEvidence += goldCase.traceableEvidenceItems();
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
        for (GoldCase goldCase : cases) {
            if (RETRIEVAL.equals(goldCase.scenario())) continue;
            accumulate(goldCase, predictions.get(goldCase.caseId()), overall);
        }
        double completeness = totalEvidence == 0 ? 1.0 : (double) traceableEvidence / totalEvidence;
        long averageLatencyMs = predictions.isEmpty() ? 0 : totalLatencyMs / predictions.size();
        return new GoldEvalReport(total, extraction, retrieval, List.copyOf(scenarios),
                overall.metrics(), completeness, Map.of(
                        "negativeScenarios", NEGATIVE_SCENARIOS,
                        "retrievalExcludedFromExtraction", true,
                        "predictionStatusCounts", statusCounts,
                        "totalLatencyMs", totalLatencyMs,
                        "averageLatencyMs", averageLatencyMs));
    }

    private void accumulate(GoldCase goldCase, Prediction prediction, Accumulator accumulator) {
        accumulator.cases++;
        Set<String> predictedNames = prediction.entities() == null ? Set.of() : prediction.entities();

        for (String predicted : predictedNames) {
            if (matchesAnyEntity(predicted, goldCase.entities())) {
                accumulator.entityTp++;
            } else {
                accumulator.entityFp++;
            }
        }
        for (GoldEntity gold : goldCase.entities()) {
            boolean matched = predictedNames.stream().anyMatch(predicted -> matchesAnyEntity(predicted, List.of(gold)));
            if (!matched) {
                accumulator.entityFn++;
            }
        }

        List<PredictedRelation> predictedRelations = prediction.relations() == null ? List.of() : prediction.relations();
        List<GoldRelation> goldRelations = goldCase.relations() == null ? List.of() : goldCase.relations();
        Map<String, String> idToName = entityIdToName(goldCase.entities());
        for (PredictedRelation predicted : predictedRelations) {
            if (matchesAnyRelation(predicted, goldRelations, idToName)) {
                accumulator.relationTp++;
            } else {
                accumulator.relationFp++;
            }
        }
        for (GoldRelation gold : goldRelations) {
            if (!matchesAnyPredictedRelation(gold, predictedRelations, idToName)) {
                accumulator.relationFn++;
            }
        }

        List<RequirementGraphGoldModels.PredictedClaim> predictedClaims =
                prediction.claims() == null ? List.of() : prediction.claims();
        List<GoldClaim> goldClaims = goldCase.claims() == null ? List.of() : goldCase.claims();
        for (RequirementGraphGoldModels.PredictedClaim predicted : predictedClaims) {
            if (matchesAnyClaim(predicted, goldClaims)) accumulator.claimTp++;
            else accumulator.claimFp++;
        }
        for (GoldClaim gold : goldClaims) {
            if (matchesAnyPredictedClaim(gold, predictedClaims)) accumulator.claimFnAddIfNotTp();
            else accumulator.claimFn++;
        }

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

        // 代码事实召回：直接比较 GoldCodeFact 与 PredictedCodeFact（含 repository/commit/factKey/value）
        List<RequirementGraphGoldModels.GoldCodeFact> goldCodeFacts = goldCase.codeFacts() == null
                ? List.of() : goldCase.codeFacts();
        List<RequirementGraphGoldModels.PredictedCodeFact> predictedCodeFacts = prediction.codeFacts() == null
                ? List.of() : prediction.codeFacts();
        for (RequirementGraphGoldModels.GoldCodeFact goldFact : goldCodeFacts) {
            boolean matched = predictedCodeFacts.stream()
                    .anyMatch(predictedCodeFact -> matchesCodeFact(predictedCodeFact, goldFact));
            if (matched) {
                accumulator.codeTp++;
            } else {
                accumulator.codeFn++;
            }
        }

        if (DRIFT_SCENARIOS.contains(goldCase.scenario())) {
            accumulator.driftCases++;
            if (isDriftCorrect(goldCase.scenario(), prediction)) {
                accumulator.driftCorrect++;
            }
        }
    }

    private boolean isDriftCorrect(String scenario, Prediction prediction) {
        String driftType = normalize(prediction.driftDecision() == null ? "" : prediction.driftDecision().type());
        RequirementGraphGoldModels.PublicationDecision publication = prediction.publicationDecision();
        return switch (scenario) {
            case "DOCUMENT_DRIFT_REVIEW" -> publication == RequirementGraphGoldModels.PublicationDecision.REVIEW_REQUIRED
                    || driftType.equals("DOCUMENT_DRIFT") || driftType.equals("REVIEW_REQUIRED");
            case "DOCUMENT_CONFLICT" -> publication == RequirementGraphGoldModels.PublicationDecision.PRESERVE_CONFLICT
                    || driftType.contains("CONFLICT");
            case "OPEN_DOUBT_NO_DRIFT" -> driftType.equals("OPEN")
                    || publication == RequirementGraphGoldModels.PublicationDecision.NOT_PUBLISHED;
            case "NO_DRIFT_CODE_BOUNDARY" -> driftType.equals("NO_DRIFT")
                    || (publication == RequirementGraphGoldModels.PublicationDecision.PUBLISH
                        && !driftType.contains("DRIFT"));
            default -> false;
        };
    }

    private boolean matchesCodeFact(RequirementGraphGoldModels.PredictedCodeFact predicted,
                                    RequirementGraphGoldModels.GoldCodeFact gold) {
        boolean keyOk = predicted.factKey() != null && !predicted.factKey().isBlank()
                && normalize(predicted.factKey()).equals(normalize(gold.factKey()));
        boolean valueOk = predicted.value() != null && !predicted.value().isBlank()
                && normalize(predicted.value()).equals(normalize(gold.value()));
        boolean repoOk = gold.repositoryId() == null || gold.repositoryId().isBlank()
                || normalize(predicted.repositoryId()).equals(normalize(gold.repositoryId()));
        boolean commitOk = gold.commitSha() == null || gold.commitSha().isBlank()
                || normalize(predicted.commitSha()).equals(normalize(gold.commitSha()));
        return (keyOk || valueOk) && repoOk && commitOk;
    }

    private Map<String, String> entityIdToName(List<GoldEntity> entities) {
        Map<String, String> map = new LinkedHashMap<>();
        for (GoldEntity entity : entities) {
            map.putIfAbsent(entity.id(), entity.canonicalName());
        }
        return map;
    }

    private boolean matchesAnyEntity(String predicted, List<GoldEntity> goldEntities) {
        String normalized = normalize(predicted);
        if (normalized.isBlank()) return false;
        for (GoldEntity entity : goldEntities) {
            if (normalized.equals(normalize(entity.canonicalName()))) return true;
            for (String alias : entity.aliases()) {
                if (normalized.equals(normalize(alias))) return true;
            }
            if (containsOrContained(normalized, normalize(entity.canonicalName()))) return true;
        }
        return false;
    }

    private boolean matchesAnyRelation(PredictedRelation predicted, List<GoldRelation> goldRelations,
                                       Map<String, String> idToName) {
        String predicate = normalize(predicted.predicate());
        for (GoldRelation gold : goldRelations) {
            String goldSource = resolveName(gold.subject(), idToName);
            String goldTarget = resolveName(gold.object(), idToName);
            if (predicate.equals(normalize(gold.predicate()))
                    && nameMatches(predicted.source(), goldSource)
                    && nameMatches(predicted.target(), goldTarget)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyPredictedRelation(GoldRelation gold, List<PredictedRelation> predictedRelations,
                                                Map<String, String> idToName) {
        String goldSource = resolveName(gold.subject(), idToName);
        String goldTarget = resolveName(gold.object(), idToName);
        for (PredictedRelation predicted : predictedRelations) {
            if (normalize(gold.predicate()).equals(normalize(predicted.predicate()))
                    && nameMatches(predicted.source(), goldSource)
                    && nameMatches(predicted.target(), goldTarget)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyClaim(RequirementGraphGoldModels.PredictedClaim predicted, List<GoldClaim> goldClaims) {
        for (GoldClaim gold : goldClaims) {
            boolean keyMatch = !predicted.factKey().isBlank() && normalize(predicted.factKey()).equals(normalize(gold.factKey()));
            boolean valueMatch = !predicted.value().isBlank() && normalize(predicted.value()).equals(normalize(gold.value()));
            if (keyMatch || valueMatch) return true;
        }
        return false;
    }

    private boolean matchesAnyPredictedClaim(GoldClaim gold, List<RequirementGraphGoldModels.PredictedClaim> predictedClaims) {
        for (RequirementGraphGoldModels.PredictedClaim predicted : predictedClaims) {
            boolean keyMatch = !predicted.factKey().isBlank() && normalize(predicted.factKey()).equals(normalize(gold.factKey()));
            boolean valueMatch = !predicted.value().isBlank() && normalize(predicted.value()).equals(normalize(gold.value()));
            if (keyMatch || valueMatch) return true;
        }
        return false;
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

    private String resolveName(String id, Map<String, String> idToName) {
        String name = idToName.get(id);
        return name == null ? id : name;
    }

    private boolean nameMatches(String predicted, String gold) {
        return normalize(predicted).equals(normalize(gold))
                || containsOrContained(normalize(predicted), normalize(gold));
    }

    private boolean containsOrContained(String left, String right) {
        if (left.isBlank() || right.isBlank()) return false;
        if (left.equals(right)) return true;
        if (left.length() < 2 || right.length() < 2) return false;
        return left.contains(right) || right.contains(left);
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s|｜:：（）()\\[\\]【】、，,。.;；/\\\\_\\-]+", "");
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
        private int codeFn;
        private int negativeCases;
        private int negativeErrors;
        private int driftCases;
        private int driftCorrect;
        private int cases;

        Accumulator(String scenario) {
            this.scenario = scenario;
        }

        void entityFnAddIfNotTp() {
            // 金标实体若已被某预测命中则不算 FN（避免重复计数）；由 tp 计数覆盖。
        }

        void claimFnAddIfNotTp() {
        }

        void codeFnAddIfNotTp() {
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
            double negativeErrorRate = negativeCases == 0 ? Double.NaN : (double) negativeErrors / negativeCases;
            double driftAccuracy = driftCases == 0 ? Double.NaN : (double) driftCorrect / driftCases;
            return new ScenarioMetrics(scenario, cases,
                    entityPrecision, entityRecall, f1(entityPrecision, entityRecall),
                    relationPrecision, relationRecall, f1(relationPrecision, relationRecall),
                    claimPrecision, claimRecall, f1(claimPrecision, claimRecall),
                    negativeErrorRate, uncertaintyRecall, codeFactRecall, driftAccuracy);
        }

        private double ratio(int numerator, int denominator) {
            return denominator == 0 ? 0.0 : (double) numerator / denominator;
        }

        private double f1(double precision, double recall) {
            return precision + recall == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
        }
    }
}