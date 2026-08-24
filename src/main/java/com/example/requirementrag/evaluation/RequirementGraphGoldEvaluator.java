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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    public GoldEvalReport evaluate(List<GoldCase> cases, RequirementGraphGoldPredictor predictor) {
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
            accumulate(goldCase, predictor.predict(goldCase), accumulator);
        }

        List<ScenarioMetrics> scenarios = new ArrayList<>();
        for (Accumulator accumulator : byScenario.values()) {
            scenarios.add(accumulator.metrics());
        }
        Accumulator overall = new Accumulator("OVERALL");
        for (GoldCase goldCase : cases) {
            if (RETRIEVAL.equals(goldCase.scenario())) continue;
            accumulate(goldCase, predictor.predict(goldCase), overall);
        }
        double traceability = totalEvidence == 0 ? 1.0 : (double) traceableEvidence / totalEvidence;
        return new GoldEvalReport(total, extraction, retrieval, List.copyOf(scenarios),
                overall.metrics(), traceability, Map.of(
                        "negativeScenarios", NEGATIVE_SCENARIOS,
                        "retrievalExcludedFromExtraction", true));
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
                if ("IMPLEMENTED_BY".equalsIgnoreCase(normalize(predicted.predicate()))) {
                    accumulator.codeTp++;
                }
            } else {
                accumulator.relationFp++;
            }
        }
        for (GoldRelation gold : goldRelations) {
            String predicate = normalize(gold.predicate());
            if (matchesAnyPredictedRelation(gold, predictedRelations, idToName)) {
                if ("IMPLEMENTED_BY".equals(predicate)) accumulator.codeFnAddIfNotTp();
            } else {
                accumulator.relationFn++;
                if ("IMPLEMENTED_BY".equals(predicate)) accumulator.codeFn++;
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
            if ((prediction.relations() != null && !prediction.relations().isEmpty())
                    || (prediction.claims() != null && !prediction.claims().isEmpty())) {
                accumulator.negativeErrors++;
            }
        }
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
            return new ScenarioMetrics(scenario, cases,
                    entityPrecision, entityRecall, f1(entityPrecision, entityRecall),
                    relationPrecision, relationRecall, f1(relationPrecision, relationRecall),
                    claimPrecision, claimRecall, f1(claimPrecision, claimRecall),
                    negativeErrorRate, uncertaintyRecall, codeFactRecall);
        }

        private double ratio(int numerator, int denominator) {
            return denominator == 0 ? 0.0 : (double) numerator / denominator;
        }

        private double f1(double precision, double recall) {
            return precision + recall == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
        }
    }
}