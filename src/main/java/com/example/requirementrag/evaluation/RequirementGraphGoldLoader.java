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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 加载 requirement-graph-gold JSONL 数据集并规范化为评测用例。
 *
 * <p>支持两种加载模式：
 * <ul>
 *   <li>{@link GoldLoadMode#EXPLORATORY}：允许 {@code HUMAN_REVIEW_REQUIRED} 记录，漂移用例缺少显式
 *       {@code decision} 时从 scenario 兼容推导——用于快速调试；</li>
 *   <li>{@link GoldLoadMode#FORMAL}：只允许 {@code GOLD_ACCEPTED}，且漂移用例必须携带显式
 *       {@code decision}，禁止隐式推导——用于 CI / 模型对比 / 上线门禁。</li>
 * </ul>
 *
 * <p>两种模式都执行结构完整性校验：caseId 非空且唯一、scenario 非空、entity id 唯一、
 * claim/relation/decision 引用的 evidenceId 必须存在、annotation.status 非空。
 */
public final class RequirementGraphGoldLoader {

    /** 金标加载模式。 */
    public enum GoldLoadMode {
        /** 允许未审核记录，decision 可兼容推导（调试用）。 */
        EXPLORATORY,
        /** 只允许 GOLD_ACCEPTED，禁止 decision 隐式推导（正式门禁）。 */
        FORMAL
    }

    private static final Set<String> DRIFT_SCENARIOS = Set.of(
            "DOCUMENT_DRIFT_REVIEW", "DOCUMENT_CONFLICT", "OPEN_DOUBT_NO_DRIFT", "NO_DRIFT_CODE_BOUNDARY");

    private final ObjectMapper objectMapper;

    public RequirementGraphGoldLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 兼容旧调用：默认探索模式。 */
    public List<GoldCase> load(Path path) {
        return load(path, GoldLoadMode.EXPLORATORY);
    }

    public List<GoldCase> load(Path path, GoldLoadMode mode) {
        List<GoldCase> cases = new ArrayList<>();
        Set<String> seenCaseIds = new HashSet<>();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line == null || line.isBlank()) continue;
                JsonNode node = objectMapper.readTree(line);
                validate(node, seenCaseIds, mode);
                cases.add(parse(node, mode));
            }
            return List.copyOf(cases);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取金标数据集: " + path, exception);
        }
    }

    private void validate(JsonNode node, Set<String> seenCaseIds, GoldLoadMode mode) {
        String caseId = node.path("caseId").asText();
        if (caseId.isBlank()) {
            throw new IllegalStateException("金标记录缺少 caseId");
        }
        if (!seenCaseIds.add(caseId)) {
            throw new IllegalStateException("金标 caseId 重复: " + caseId);
        }
        String scenario = node.path("scenario").asText();
        if (scenario.isBlank()) {
            throw new IllegalStateException("金标记录缺少 scenario: " + caseId);
        }
        JsonNode gold = node.path("gold");
        String annotationStatus = node.path("annotation").path("status").asText("");
        if (annotationStatus.isBlank()) {
            throw new IllegalStateException("金标 annotation.status 为空: " + caseId);
        }
        if (mode == GoldLoadMode.FORMAL && !"GOLD_ACCEPTED".equals(annotationStatus)) {
            throw new IllegalStateException("正式评测只允许 GOLD_ACCEPTED，但 " + caseId + " 是 " + annotationStatus);
        }
        // entity id 唯一
        Set<String> entityIds = new HashSet<>();
        for (JsonNode entity : gold.path("entities")) {
            String id = entity.path("id").asText();
            if (id.isBlank()) throw new IllegalStateException("金标实体缺少 id: " + caseId);
            if (!entityIds.add(id)) throw new IllegalStateException("金标实体 id 重复: " + caseId + " / " + id);
        }
        // evidenceId 索引
        Set<String> evidenceIds = new HashSet<>();
        for (JsonNode evidence : gold.path("evidence")) {
            String evidenceId = evidence.path("evidenceId").asText();
            if (evidenceId.isBlank()) throw new IllegalStateException("金标 evidence 缺少 evidenceId: " + caseId);
            evidenceIds.add(evidenceId);
        }
        // claim / relation / decision 引用的 evidence 必须存在
        for (JsonNode relation : gold.path("relations")) {
            requireEvidence(relation, caseId, evidenceIds);
            if (relation.path("subject").asText("").isBlank() || relation.path("object").asText("").isBlank()) {
                throw new IllegalStateException("金标关系端点为空: " + caseId);
            }
        }
        for (JsonNode claim : gold.path("claims")) {
            requireEvidence(claim, caseId, evidenceIds);
        }
        if (gold.has("decision") && !gold.path("decision").isNull()) {
            JsonNode decision = gold.path("decision");
            if (!decision.isMissingNode() && !decision.isNull()) {
                requireEvidence(decision, caseId, evidenceIds);
            }
        }
        // FORMAL：漂移用例必须显式 decision，禁止从 scenario 推导
        if (mode == GoldLoadMode.FORMAL && DRIFT_SCENARIOS.contains(scenario)) {
            JsonNode decision = gold.path("decision");
            if (decision.isMissingNode() || decision.isNull()
                    || decision.path("type").asText("").isBlank()
                    || decision.path("publication").asText("").isBlank()) {
                throw new IllegalStateException("正式评测要求漂移用例显式 decision{type,publication}: " + caseId);
            }
        }
    }

    private void requireEvidence(JsonNode node, String caseId, Set<String> evidenceIds) {
        for (JsonNode evidenceId : node.path("evidenceIds")) {
            String id = evidenceId.asText();
            if (id.isBlank() || !evidenceIds.contains(id)) {
                throw new IllegalStateException("金标引用不存在的 evidenceId(" + id + "): " + caseId);
            }
        }
    }

    private GoldCase parse(JsonNode node, GoldLoadMode mode) {
        String caseId = node.path("caseId").asText();
        String scenario = node.path("scenario").asText();
        String projectId = node.path("projectId").asText();
        String documentId = node.path("documentId").asText();
        String requirementVersion = node.path("requirementVersion").asText();
        String annotationStatus = node.path("annotation").path("status").asText("");
        JsonNode input = node.path("input");
        String inputText = resolveInputText(input);
        List<GoldWindow> windows = windows(input.path("windows"));
        List<GoldCodeFact> codeFactInputs = codeFacts(input.path("codeFacts"));
        JsonNode gold = node.path("gold");
        EvidenceStats evidenceStats = evidenceStats(gold.path("evidence"));
        GoldDecision decision = resolveDecision(gold.path("decision"), scenario, mode, caseId);
        return new GoldCase(
                caseId,
                scenario,
                inputText,
                windows,
                entities(gold.path("entities")),
                relations(gold.path("relations")),
                claims(gold.path("claims")),
                uncertainties(gold.path("uncertainties")),
                codeFacts(gold.path("codeFacts")),
                decision,
                codeFactInputs,
                evidenceStats.items(),
                evidenceStats.total(),
                evidenceStats.traceable(),
                projectId,
                documentId,
                requirementVersion,
                annotationStatus);
    }

    private String resolveInputText(JsonNode input) {
        JsonNode text = input.path("text");
        if (text.isTextual() && !text.asText().isBlank()) return text.asText();
        JsonNode windows = input.path("windows");
        if (windows.isArray() && windows.size() > 0) {
            // 保留逐窗口语义：仅当调用方需要“单文本”形态时拼装；跨窗口评测应使用 windows()。
            StringBuilder builder = new StringBuilder();
            for (JsonNode window : windows) {
                if (builder.length() > 0) builder.append("\n--- WINDOW ---\n");
                builder.append(window.path("text").asText(""));
            }
            return builder.toString();
        }
        JsonNode query = input.path("query");
        if (query.isTextual()) return query.asText();
        return "";
    }

    private List<GoldWindow> windows(JsonNode array) {
        if (array == null || !array.isArray()) return List.of();
        List<GoldWindow> result = new ArrayList<>();
        for (JsonNode item : array) {
            result.add(new GoldWindow(
                    item.path("windowId").asText(),
                    item.path("index").asInt(0),
                    item.path("parentId").asText(),
                    item.path("parentOrder").asInt(0),
                    item.path("filename").asText(),
                    item.path("startOffset").asInt(0),
                    item.path("endOffset").asInt(0),
                    item.path("contentHash").asText(),
                    item.path("text").asText("")));
        }
        return List.copyOf(result);
    }

    private GoldDecision resolveDecision(JsonNode decision, String scenario, GoldLoadMode mode, String caseId) {
        if (decision != null && !decision.isMissingNode() && !decision.isNull()) {
            List<String> evidenceIds = new ArrayList<>();
            for (JsonNode evidence : decision.path("evidenceIds")) evidenceIds.add(evidence.asText());
            return new GoldDecision(
                    decision.path("type").asText(),
                    decision.path("status").asText(),
                    decision.path("publication").asText(),
                    evidenceIds);
        }
        if (mode == GoldLoadMode.FORMAL) {
            throw new IllegalStateException("正式评测要求漂移用例显式 decision: " + caseId);
        }
        // 探索模式兼容旧数据：从场景推导评测期望（新数据集应写入显式 decision）。
        return switch (scenario) {
            case "DOCUMENT_DRIFT_REVIEW" -> new GoldDecision("DOCUMENT_DRIFT", "REVIEW_REQUIRED", "REVIEW_REQUIRED", List.of());
            case "DOCUMENT_CONFLICT" -> new GoldDecision("DOCUMENT_CONFLICT", "CONFLICT", "PRESERVE_CONFLICT", List.of());
            case "OPEN_DOUBT_NO_DRIFT" -> new GoldDecision("OPEN", "OPEN", "NOT_PUBLISHED", List.of());
            case "NO_DRIFT_CODE_BOUNDARY" -> new GoldDecision("NO_DRIFT", "ALIGNED", "PUBLISH", List.of());
            default -> new GoldDecision("", "", "", List.of());
        };
    }

    private EvidenceStats evidenceStats(JsonNode evidenceArray) {
        int total = 0;
        int traceable = 0;
        List<GoldEvidenceItem> items = new ArrayList<>();
        for (JsonNode evidence : evidenceArray) {
            String evidenceId = evidence.path("evidenceId").asText();
            for (JsonNode item : evidence.path("items")) {
                total++;
                String sourceFile = item.path("sourceFile").asText();
                String quote = item.path("quote").asText();
                boolean hasOffset = item.hasNonNull("startOffset") || item.hasNonNull("offset");
                int start = item.path("startOffset").asInt(item.path("offset").asInt(-1));
                int end = item.path("endOffset").asInt(-1);
                boolean hasWindowId = item.hasNonNull("windowId");
                boolean hasContentHash = item.hasNonNull("contentHash");
                items.add(new GoldEvidenceItem(
                        evidenceId,
                        item.path("sourceType").asText(),
                        sourceFile,
                        quote,
                        hasOffset,
                        start,
                        end,
                        hasWindowId,
                        item.path("windowId").asText(),
                        hasContentHash,
                        item.path("contentHash").asText(),
                        item.path("expected").asText()));
                if (!sourceFile.isBlank() && !quote.isBlank()) traceable++;
            }
        }
        return new EvidenceStats(List.copyOf(items), total, traceable);
    }

    private List<GoldEntity> entities(JsonNode array) {
        List<GoldEntity> result = new ArrayList<>();
        for (JsonNode item : array) {
            List<String> aliases = new ArrayList<>();
            for (JsonNode alias : item.path("aliases")) aliases.add(alias.asText());
            result.add(new GoldEntity(item.path("id").asText(), item.path("type").asText(),
                    item.path("canonicalName").asText(), aliases));
        }
        return List.copyOf(result);
    }

    private List<GoldRelation> relations(JsonNode array) {
        List<GoldRelation> result = new ArrayList<>();
        for (JsonNode item : array) {
            List<String> evidenceIds = new ArrayList<>();
            for (JsonNode evidence : item.path("evidenceIds")) evidenceIds.add(evidence.asText());
            result.add(new GoldRelation(item.path("subject").asText(), item.path("predicate").asText(),
                    item.path("object").asText(), evidenceIds));
        }
        return List.copyOf(result);
    }

    private List<GoldClaim> claims(JsonNode array) {
        List<GoldClaim> result = new ArrayList<>();
        for (JsonNode item : array) {
            List<String> evidenceIds = new ArrayList<>();
            for (JsonNode evidence : item.path("evidenceIds")) evidenceIds.add(evidence.asText());
            result.add(new GoldClaim(item.path("factKey").asText(), item.path("value").asText(),
                    item.path("certainty").asText(), evidenceIds));
        }
        return List.copyOf(result);
    }

    private List<GoldUncertainty> uncertainties(JsonNode array) {
        List<GoldUncertainty> result = new ArrayList<>();
        for (JsonNode item : array) {
            result.add(new GoldUncertainty(item.path("uncertaintyId").asText(item.path("id").asText()),
                    item.path("status").asText(), item.path("type").asText(),
                    item.path("question").asText(item.path("subject").asText())));
        }
        return List.copyOf(result);
    }

    private List<GoldCodeFact> codeFacts(JsonNode array) {
        List<GoldCodeFact> result = new ArrayList<>();
        for (JsonNode item : array) {
            List<String> symbols = new ArrayList<>();
            for (JsonNode symbol : item.path("symbolNames")) symbols.add(symbol.asText());
            result.add(new GoldCodeFact(item.path("repositoryId").asText(), item.path("commitSha").asText(),
                    item.path("factKey").asText(), item.path("value").asText(), symbols));
        }
        return List.copyOf(result);
    }

    private record EvidenceStats(List<GoldEvidenceItem> items, int total, int traceable) {
    }
}