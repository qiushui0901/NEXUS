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
import java.util.List;

/** 加载 requirement-graph-gold JSONL 数据集并规范化为评测用例。 */
public final class RequirementGraphGoldLoader {

    private final ObjectMapper objectMapper;

    public RequirementGraphGoldLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<GoldCase> load(Path path) {
        List<GoldCase> cases = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line == null || line.isBlank()) continue;
                JsonNode node = objectMapper.readTree(line);
                cases.add(parse(node));
            }
            return List.copyOf(cases);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取金标数据集: " + path, exception);
        }
    }

    private GoldCase parse(JsonNode node) {
        String caseId = node.path("caseId").asText();
        String scenario = node.path("scenario").asText();
        JsonNode input = node.path("input");
        String inputText = resolveInputText(input);
        List<GoldWindow> windows = windows(input.path("windows"));
        List<GoldCodeFact> codeFactInputs = codeFacts(input.path("codeFacts"));
        JsonNode gold = node.path("gold");
        EvidenceStats evidenceStats = evidenceStats(gold.path("evidence"));
        GoldDecision decision = resolveDecision(gold.path("decision"), scenario);
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
                evidenceStats.traceable());
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

    private GoldDecision resolveDecision(JsonNode decision, String scenario) {
        if (decision != null && !decision.isMissingNode() && !decision.isNull()) {
            List<String> evidenceIds = new ArrayList<>();
            for (JsonNode evidence : decision.path("evidenceIds")) evidenceIds.add(evidence.asText());
            return new GoldDecision(
                    decision.path("type").asText(),
                    decision.path("status").asText(),
                    decision.path("publication").asText(),
                    evidenceIds);
        }
        // 兼容旧数据：金标没有显式 decision 时，从场景推导评测期望（新数据集应写入显式 decision）。
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