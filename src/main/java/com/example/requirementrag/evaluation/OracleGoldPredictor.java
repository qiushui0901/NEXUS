package com.example.requirementrag.evaluation;

import com.example.requirementrag.evaluation.RequirementGraphGoldModels.DriftDecision;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldDecision;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldEntity;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldRelation;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldUncertainty;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictedClaim;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictedCodeFact;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictedEntity;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictedRelation;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.Prediction;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictionStatus;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PublicationDecision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Oracle 预测器：直接把 Gold 内容输出为预测。
 *
 * <p>用于评测器自检——若 Oracle 不能达到接近 1.0 的实体/关系/Claim F1、
 * 0 负例错误率，则说明评测器本身有问题，不能继续调模型。
 *
 * <p>不根据场景名猜评测器期望：decision 直接复制 Gold 中显式的
 * {@code decision} 字段（不存在时由 Loader 兼容推导）。若 Gold decision 与
 * 评测器硬编码不一致，Oracle 也会如实暴露出来，而不是“自洽通过”。
 */
public class OracleGoldPredictor implements RequirementGraphGoldPredictor {

    @Override
    public Prediction predict(GoldCase goldCase) {
        Set<PredictedEntity> entities = new LinkedHashSet<>();
        for (GoldEntity entity : goldCase.entities()) {
            if (entity.canonicalName() != null && !entity.canonicalName().isBlank()) {
                entities.add(new PredictedEntity(entity.type(), entity.canonicalName(), entity.aliases()));
            }
        }
        Map<String, String> idToName = new LinkedHashMap<>();
        for (GoldEntity entity : goldCase.entities()) {
            idToName.putIfAbsent(entity.id(), entity.canonicalName());
        }
        List<PredictedRelation> relations = new ArrayList<>();
        for (GoldRelation relation : goldCase.relations()) {
            String source = idToName.getOrDefault(relation.subject(), relation.subject());
            String target = idToName.getOrDefault(relation.object(), relation.object());
            relations.add(new PredictedRelation(source, target, relation.predicate()));
        }
        List<PredictedClaim> claims = new ArrayList<>();
        for (RequirementGraphGoldModels.GoldClaim claim : goldCase.claims()) {
            claims.add(new PredictedClaim(claim.factKey(), claim.value()));
        }
        List<String> uncertainties = new ArrayList<>();
        for (GoldUncertainty uncertainty : goldCase.uncertainties()) {
            if (uncertainty.question() != null && !uncertainty.question().isBlank()) {
                uncertainties.add(uncertainty.question());
            }
        }
        List<PredictedCodeFact> codeFacts = new ArrayList<>();
        for (RequirementGraphGoldModels.GoldCodeFact fact : goldCase.codeFacts()) {
            codeFacts.add(new PredictedCodeFact(fact.repositoryId(), fact.commitSha(),
                    fact.factKey(), fact.value(), fact.symbolNames()));
        }
        GoldDecision decision = goldCase.decision();
        DriftDecision driftDecision = new DriftDecision(
                decision == null ? "" : decision.type(),
                decision == null ? "" : decision.status(),
                "oracle",
                decision == null ? List.of() : decision.evidenceIds());
        PublicationDecision publication = PublicationDecision.NOT_PUBLISHED;
        if (decision != null && !decision.publication().isBlank()) {
            try {
                publication = PublicationDecision.valueOf(decision.publication().trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                publication = PublicationDecision.NOT_PUBLISHED;
            }
        }
        return new Prediction(entities, relations, claims, uncertainties, codeFacts,
                driftDecision, publication, PredictionStatus.SUCCESS, "", 0, 0);
    }
}