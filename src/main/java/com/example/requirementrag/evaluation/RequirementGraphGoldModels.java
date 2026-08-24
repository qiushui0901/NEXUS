package com.example.requirementrag.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 需求语义图金标评测领域模型（v0.2）。
 *
 * <p>支持实体/关系/Claim/存疑/代码事实五类 gold 与预测，按场景聚合 Precision/Recall/F1。
 */
public final class RequirementGraphGoldModels {
    private RequirementGraphGoldModels() {
    }

    public record GoldCase(
            String caseId,
            String scenario,
            String inputText,
            List<GoldEntity> entities,
            List<GoldRelation> relations,
            List<GoldClaim> claims,
            List<GoldUncertainty> uncertainties,
            List<GoldCodeFact> codeFacts,
            int totalEvidenceItems,
            int traceableEvidenceItems
    ) {
        public GoldCase {
            entities = entities == null ? List.of() : List.copyOf(entities);
            relations = relations == null ? List.of() : List.copyOf(relations);
            claims = claims == null ? List.of() : List.copyOf(claims);
            uncertainties = uncertainties == null ? List.of() : List.copyOf(uncertainties);
            codeFacts = codeFacts == null ? List.of() : List.copyOf(codeFacts);
        }
    }

    public record GoldEntity(String id, String type, String canonicalName, List<String> aliases) {
        public GoldEntity {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    public record GoldRelation(String subject, String predicate, String object, List<String> evidenceIds) {
        public GoldRelation {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    public record GoldClaim(String factKey, String value, String certainty, List<String> evidenceIds) {
        public GoldClaim {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    public record GoldUncertainty(String id, String status, String type, String question) {
    }

    public record GoldCodeFact(String repositoryId, String commitSha, String factKey, String value) {
    }

    public record Prediction(
            Set<String> entities,
            List<PredictedRelation> relations,
            List<PredictedClaim> claims,
            List<String> uncertainties
    ) {
        public Prediction {
            entities = entities == null ? Set.of() : Set.copyOf(entities);
            relations = relations == null ? List.of() : List.copyOf(relations);
            claims = claims == null ? List.of() : List.copyOf(claims);
            uncertainties = uncertainties == null ? List.of() : List.copyOf(uncertainties);
        }
    }

    public record PredictedRelation(String source, String target, String predicate) {
    }

    public record PredictedClaim(String factKey, String value) {
    }

    public record ScenarioMetrics(
            String scenario,
            int cases,
            double entityPrecision,
            double entityRecall,
            double entityF1,
            double relationPrecision,
            double relationRecall,
            double relationF1,
            double claimPrecision,
            double claimRecall,
            double claimF1,
            double negativeErrorRate,
            double uncertaintyRecall,
            double codeFactRecall
    ) {
    }

    public record GoldEvalReport(
            int totalCases,
            int extractionCases,
            int retrievalCases,
            List<ScenarioMetrics> scenarios,
            ScenarioMetrics overall,
            double goldEvidenceTraceabilityRate,
            Map<String, Object> extras
    ) {
        public GoldEvalReport {
            scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
            extras = extras == null ? Map.of() : Map.copyOf(extras);
        }
    }
}