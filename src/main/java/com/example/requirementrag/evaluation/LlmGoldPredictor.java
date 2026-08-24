package com.example.requirementrag.evaluation;

import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictedClaim;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictedRelation;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.Prediction;
import com.example.requirementrag.requirement.graph.RequirementGraphProperties;
import com.example.requirementrag.service.GenerationChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * LLM 金标预测器：从输入文本抽取实体/关系/Claim/存疑，输出结构化 JSON。
 *
 * <p>与正式抽取共用需求图模型；任何失败/非法输出 fail-open 为空，不阻塞评测。
 */
@Component
public class LlmGoldPredictor implements RequirementGraphGoldPredictor {

    private final ChatClient chatClient;
    private final RequirementGraphProperties graphProperties;

    public LlmGoldPredictor(ChatClient chatClient, RequirementGraphProperties graphProperties) {
        this.chatClient = chatClient;
        this.graphProperties = graphProperties;
    }

    @Override
    public Prediction predict(GoldCase goldCase) {
        String text = goldCase.inputText();
        if (text == null || text.isBlank()) {
            return new Prediction(Set.of(), List.of(), List.of(), List.of());
        }
        try {
            LlmResult result = chatClient.prompt()
                    .system("""
                            你是需求语义图抽取器。从输入文本抽取实体、实体间关系、事实 Claim 与存疑。
                            实体名和关系两端必须来自输入文本，不要编造。Claim 用 factKey(小写点号路径) + value。
                            是疑问/冲突/未给出时只输出 uncertainties，不要编造确认事实。
                            输出 JSON：{"entities":["名"],"relations":[{"source":"名","target":"名","predicate":"REWARDS|REQUIRES|IMPLEMENTED_BY|HAS_FLOW|MUST_NOT_MERGE_WITH|..."}],
                            "claims":[{"factKey":"a.b.c","value":"..."}],"uncertainties":["..."]}。
                            没有则输出空数组。
                            """)
                    .user("场景：" + goldCase.scenario() + "\n文本：\n" + truncate(text))
                    .options(GenerationChatOptions.forModel(resolveModel()))
                    .call()
                    .entity(LlmResult.class);
            if (result == null) return new Prediction(Set.of(), List.of(), List.of(), List.of());
            Set<String> entities = new LinkedHashSet<>();
            if (result.entities() != null) {
                for (String entity : result.entities()) {
                    if (entity != null && !entity.isBlank()) entities.add(entity.trim());
                }
            }
            List<PredictedRelation> relations = new ArrayList<>();
            if (result.relations() != null) {
                for (LlmRelation relation : result.relations()) {
                    if (relation == null || blank(relation.source()) || blank(relation.target())) continue;
                    relations.add(new PredictedRelation(relation.source().trim(), relation.target().trim(),
                            relation.predicate() == null ? "RELATED_TO" : relation.predicate().trim()));
                }
            }
            List<PredictedClaim> claims = new ArrayList<>();
            if (result.claims() != null) {
                for (LlmClaim claim : result.claims()) {
                    if (claim == null || blank(claim.value())) continue;
                    claims.add(new PredictedClaim(claim.factKey() == null ? "" : claim.factKey().trim(),
                            claim.value().trim()));
                }
            }
            List<String> uncertainties = new ArrayList<>();
            if (result.uncertainties() != null) {
                for (String uncertainty : result.uncertainties()) {
                    if (uncertainty != null && !uncertainty.isBlank()) uncertainties.add(uncertainty.trim());
                }
            }
            return new Prediction(entities, relations, claims, uncertainties);
        } catch (RuntimeException exception) {
            return new Prediction(Set.of(), List.of(), List.of(), List.of());
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String truncate(String text) {
        return text.length() > 6000 ? text.substring(0, 6000) : text;
    }

    private String resolveModel() {
        return graphProperties.extractionModel() == null || graphProperties.extractionModel().isBlank()
                ? "deepseek-v4-flash" : graphProperties.extractionModel();
    }

    private record LlmResult(List<String> entities, List<LlmRelation> relations,
                             List<LlmClaim> claims, List<String> uncertainties) {
    }

    private record LlmRelation(String source, String target, String predicate) {
    }

    private record LlmClaim(String factKey, String value) {
    }
}