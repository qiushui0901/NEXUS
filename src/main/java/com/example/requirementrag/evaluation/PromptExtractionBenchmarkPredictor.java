package com.example.requirementrag.evaluation;

import com.example.requirementrag.evaluation.RequirementGraphGoldModels.DriftDecision;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCodeFact;
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
import java.util.Locale;
import java.util.Set;

/**
 * 提示抽取基准预测器（原 LlmGoldPredictor）。
 *
 * <p>评测的是「单次 LLM Prompt 抽取能力」，不是生产需求图抽取链路；生产链路评测请用
 * 真实 {@code RequirementGraphExtractionService / RequirementGraphBuildService / CrossWindowIntegrator}。
 *
 * <p>本轮修订：
 * <ul>
 *   <li>模型输入<b>不再包含内部场景标签</b>，避免把评测答案直接告诉模型；</li>
 *   <li>代码事实采用明确输入契约：仅当输入提供了 {@code codeFactInputs} 时模型才输出
 *       代码事实（忠实回写，而不是猜 repository/commit）；</li>
 *   <li>实现有限重试与异常分类（超时/限流/JSON失败/Schema无效/其他），retryCount 记录实际次数；</li>
 *   <li>非法 publicationDecision 返回 SCHEMA_INVALID，不再静默降级为 NOT_PUBLISHED。</li>
 * </ul>
 */
@Component
public class PromptExtractionBenchmarkPredictor implements RequirementGraphGoldPredictor {

    private final ChatClient chatClient;
    private final RequirementGraphProperties graphProperties;

    /** 最多执行 1 + MAX_RETRIES 次请求（首试 + 重试）。 */
    private static final int MAX_RETRIES = 2;

    public PromptExtractionBenchmarkPredictor(ChatClient chatClient, RequirementGraphProperties graphProperties) {
        this.chatClient = chatClient;
        this.graphProperties = graphProperties;
    }

    @Override
    public Prediction predict(GoldCase goldCase) {
        String text = goldCase.inputText();
        if ((text == null || text.isBlank()) && goldCase.windows().isEmpty()) {
            return Prediction.empty();
        }
        List<GoldCodeFact> codeFactInputs = goldCase.codeFactInputs();
        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {
            long startNanos = System.nanoTime();
            try {
                Prediction prediction = callOnce(goldCase, text, codeFactInputs);
                long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                return prediction
                        .withRetryCount(Math.max(0, attempt - 1))
                        .withLatency(latencyMs);
            } catch (RuntimeException exception) {
                long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                String code = classify(exception);
                if (!retryable(code) || attempt > MAX_RETRIES) {
                    return failure(code, latencyMs, Math.max(0, attempt - 1));
                }
                sleepBackoff(attempt);
            }
        }
        return failure("FAILURE", 0, MAX_RETRIES);
    }

    private Prediction callOnce(GoldCase goldCase, String text, List<GoldCodeFact> codeFactInputs) {
        LlmResult result = chatClient.prompt()
                .system(systemPrompt())
                .user(userPrompt(goldCase, text, codeFactInputs))
                .options(GenerationChatOptions.forModel(resolveModel()))
                .call()
                .entity(LlmResult.class);
        if (result == null) {
            return schemaFailure();
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
        if (publication == null) {
            // 模型输出了非法枚举值：不能静默当作“不发布”，明确标为 Schema 无效。
            return schemaFailure();
        }
        boolean empty = entities.isEmpty() && relations.isEmpty() && claims.isEmpty()
                && uncertainties.isEmpty() && codeFacts.isEmpty();
        PredictionStatus status = empty ? PredictionStatus.EMPTY_RESULT : PredictionStatus.SUCCESS;
        return new Prediction(entities, relations, claims, uncertainties, codeFacts,
                driftDecision, publication, status, "", 0, 0);
    }

    private String systemPrompt() {
        return """
                你是需求语义图抽取器。从输入文本抽取实体、关系、事实 Claim、存疑与发布决策。
                规则：
                1) 实体名和关系两端必须来自输入文本，不要编造。
                2) Claim 的 factKey 用“小写点号路径”，如果文本没有给出 key，用最贴近领域的规范 key（不要凭空造随机 key）。
                3) 疑问、冲突、未给出时，把内容放进 uncertainties，并让 publicationDecision=NOT_PUBLISHED；不要编造确认事实。
                4) 代码事实只在「代码事实输入」区块提供 repositoryId/commitSha/factKey/value/symbols 时，原样回写到 codeFacts；否则输出空数组。
                5) 根据输入自行判断漂移/冲突/存疑/无漂移，输出 driftDecision 与 publicationDecision；不要臆造输入没有的事实。
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
                """;
    }

    private String userPrompt(GoldCase goldCase, String text, List<GoldCodeFact> codeFactInputs) {
        StringBuilder builder = new StringBuilder();
        builder.append("需求文本：\n").append(truncate(text));
        if (codeFactInputs != null && !codeFactInputs.isEmpty()) {
            builder.append("\n\n代码事实输入（需原样回写到 codeFacts，不要修改）：\n");
            for (GoldCodeFact fact : codeFactInputs) {
                builder.append("- repositoryId=").append(fact.repositoryId())
                        .append(" commitSha=").append(fact.commitSha())
                        .append(" factKey=").append(fact.factKey())
                        .append(" value=").append(fact.value());
                if (fact.symbolNames() != null && !fact.symbolNames().isEmpty()) {
                    builder.append(" symbols=").append(String.join(",", fact.symbolNames()));
                }
                builder.append('\n');
            }
        }
        return builder.toString();
    }

    private Prediction schemaFailure() {
        return new Prediction(Set.of(), List.of(), List.of(), List.of(), List.of(),
                new DriftDecision("", "", "", List.of()), PublicationDecision.NOT_PUBLISHED,
                PredictionStatus.SCHEMA_INVALID, "SCHEMA_INVALID", 0, 0);
    }

    private Prediction failure(String errorCode, long latencyMs, int retryCount) {
        return new Prediction(Set.of(), List.of(), List.of(), List.of(), List.of(),
                new DriftDecision("", "", "", List.of()), PublicationDecision.NOT_PUBLISHED,
                PredictionStatus.FAILURE, errorCode, latencyMs, retryCount);
    }

    private PublicationDecision parsePublication(String value) {
        if (value == null || value.isBlank()) return PublicationDecision.NOT_PUBLISHED;
        try {
            return PublicationDecision.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String classify(RuntimeException exception) {
        String text = ((exception == null ? "null" : exception.getClass().getSimpleName())
                + " " + (exception == null ? "" : exception.getMessage())).toLowerCase(Locale.ROOT);
        if (text.contains("timeout") || text.contains("timed out")) return "MODEL_TIMEOUT";
        if (text.contains("429") || text.contains("rate limit") || text.contains("rate_limit")
                || text.contains("too many requests")) return "MODEL_RATE_LIMITED";
        if (text.contains("json") || text.contains("parse") || text.contains("unrecognized")
                || text.contains("deserialize") || text.contains("mapper")) return "JSON_PARSE_FAILED";
        if (text.contains("schema") || text.contains("invalid") || text.contains("evidence")) return "SCHEMA_INVALID";
        return "FAILURE";
    }

    private boolean retryable(String errorCode) {
        return "MODEL_TIMEOUT".equals(errorCode) || "MODEL_RATE_LIMITED".equals(errorCode);
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(Math.min(2_000L, 200L * (1L << Math.min(attempt - 1, 3))));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String defaultString(String value) {
        return value == null ? "" : value.trim();
    }

    private String truncate(String text) {
        return text == null ? "" : text.length() > 3000 ? text.substring(0, 3000) : text;
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
