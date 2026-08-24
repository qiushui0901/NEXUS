package com.example.requirementrag.evaluation;

import com.example.requirementrag.evaluation.RequirementGraphGoldModels.DriftDecision;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictedClaim;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictedCodeFact;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictedRelation;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.Prediction;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictionStatus;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PublicationDecision;
import com.example.requirementrag.requirement.graph.RequirementGraphProperties;
import com.example.requirementrag.service.GenerationChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * LLM 金标预测器：抽取实体/关系/Claim/存疑/代码事实，并输出漂移/发布决策。
 *
 * <p>不再吞掉异常：调用失败返回 FAILURE + errorCode + latencyMs，报告可区分
 * “真没抽到 / JSON失败 / 超时 / 限流 / 不可用”。
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
        long startNanos = System.nanoTime();
        String text = goldCase.inputText();
        if (text == null || text.isBlank()) {
            return Prediction.empty();
        }
        try {
            LlmResult result = chatClient.prompt()
                    .system("""
                            你是需求语义图抽取器。从输入文本抽取实体、关系、事实 Claim、存疑、代码事实与发布决策。
                            规则：
                            1) 实体名和关系两端必须来自输入文本，不要编造。
                            2) Claim 的 factKey 用“小写点号路径”，如果文本没有给出 key，用最贴近领域的规范 key（不要凭空造随机 key）。
                            3) 疑问、冲突、未给出时，把内容放进 uncertainties，并让 publicationDecision=NOT_PUBLISHED；不要编造确认事实。
                            4) 代码事实只在文本提供 repository/commit/符号时输出。
                            5) 漂移/冲突场景输出 driftDecision：DOCUMENT_DRIFT_REVIEW→type=DOCUMENT_DRIFT/status=REVIEW_REQUIRED；
                               DOCUMENT_CONFLICT→type=DOCUMENT_CONFLICT/status=CONFLICT/publication=PRESERVE_CONFLICT；
                               OPEN_DOUBT_NO_DRIFT→type=OPEN/publication=NOT_PUBLISHED；NO_DRIFT→type=NO_DRIFT。
                            输出 JSON：
                            {
                              "entities":["名"],
                              "relations":[{"source":"名","target":"名","predicate":"..."}],
                              "claims":[{"factKey":"a.b.c","value":"..."}],
                              "uncertainties":["..."],
                              "codeFacts":[{"repositoryId":"...","commitSha":"...","factKey":"...","value":"...","symbols":["..."]}],
                              "driftDecision":{"type":"","status":"","reason":"","evidenceIds":[]},
                              "publicationDecision":"PUBLISH|REVIEW_REQUIRED|PRESERVE_CONFLICT|NOT_PUBLISHED"
                            }
                            没有则空数组/空字符串。
                            """)
                    .user("场景：" + goldCase.scenario() + "\n文本：\n" + truncate(text))
                    .options(GenerationChatOptions.forModel(resolveModel()))
                    .call()
                    .entity(LlmResult.class);
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (result == null) {
                return failure("SCHEMA_INVALID", latencyMs);
            }
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
            List<PredictedCodeFact> codeFacts = new ArrayList<>();
            if (result.codeFacts() != null) {
                for (LlmCodeFact fact : result.codeFacts()) {
                    if (fact == null || blank(fact.factKey())) continue;
                    codeFacts.add(new PredictedCodeFact(fact.repositoryId() == null ? "" : fact.repositoryId().trim(),
                            fact.commitSha() == null ? "" : fact.commitSha().trim(),
                            fact.factKey().trim(), fact.value() == null ? "" : fact.value().trim(),
                            fact.symbols() == null ? List.of() : List.copyOf(fact.symbols())));
                }
            }
            DriftDecision driftDecision = new DriftDecision(
                    result.driftDecision() == null ? "" : defaultString(result.driftDecision().type()),
                    result.driftDecision() == null ? "" : defaultString(result.driftDecision().status()),
                    result.driftDecision() == null ? "" : defaultString(result.driftDecision().reason()),
                    result.driftDecision() == null || result.driftDecision().evidenceIds() == null
                            ? List.of() : List.copyOf(result.driftDecision().evidenceIds()));
            PublicationDecision publication = parsePublication(result.publicationDecision());
            boolean empty = entities.isEmpty() && relations.isEmpty() && claims.isEmpty()
                    && uncertainties.isEmpty() && codeFacts.isEmpty();
            PredictionStatus status = empty ? PredictionStatus.EMPTY_RESULT : PredictionStatus.SUCCESS;
            return new Prediction(entities, relations, claims, uncertainties, codeFacts,
                    driftDecision, publication, status, "", latencyMs, 0);
        } catch (RuntimeException exception) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            return failure("MODEL_FAILURE", latencyMs);
        }
    }

    private Prediction failure(String errorCode, long latencyMs) {
        return new Prediction(Set.of(), List.of(), List.of(), List.of(), List.of(),
                new DriftDecision("", "", "", List.of()), PublicationDecision.NOT_PUBLISHED,
                PredictionStatus.FAILURE, errorCode, latencyMs, 1);
    }

    private PublicationDecision parsePublication(String value) {
        if (value == null || value.isBlank()) return PublicationDecision.NOT_PUBLISHED;
        try {
            return PublicationDecision.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return PublicationDecision.NOT_PUBLISHED;
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String defaultString(String value) {
        return value == null ? "" : value.trim();
    }

    private String truncate(String text) {
        return text.length() > 3000 ? text.substring(0, 3000) : text;
    }

    private String resolveModel() {
        return graphProperties.extractionModel() == null || graphProperties.extractionModel().isBlank()
                ? "deepseek-v4-flash" : graphProperties.extractionModel();
    }

    private record LlmResult(List<String> entities, List<LlmRelation> relations, List<LlmClaim> claims,
                             List<String> uncertainties, List<LlmCodeFact> codeFacts,
                             LlmDriftDecision driftDecision, String publicationDecision) {
    }

    private record LlmRelation(String source, String target, String predicate) {
    }

    private record LlmClaim(String factKey, String value) {
    }

    private record LlmCodeFact(String repositoryId, String commitSha, String factKey, String value,
                               List<String> symbols) {
    }

    private record LlmDriftDecision(String type, String status, String reason, List<String> evidenceIds) {
    }
}