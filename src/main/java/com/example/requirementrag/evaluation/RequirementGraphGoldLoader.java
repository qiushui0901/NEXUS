package com.example.requirementrag.evaluation;

import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldClaim;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCodeFact;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldEntity;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldRelation;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldUncertainty;
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
        JsonNode gold = node.path("gold");
        int totalEvidenceItems = 0;
        int traceableEvidenceItems = 0;
        for (JsonNode evidence : gold.path("evidence")) {
            for (JsonNode item : evidence.path("items")) {
                totalEvidenceItems++;
                String sourceFile = item.path("sourceFile").asText();
                String quote = item.path("quote").asText();
                if (!sourceFile.isBlank() && !quote.isBlank()) traceableEvidenceItems++;
            }
        }
        return new GoldCase(
                caseId,
                scenario,
                inputText,
                entities(gold.path("entities")),
                relations(gold.path("relations")),
                claims(gold.path("claims")),
                uncertainties(gold.path("uncertainties")),
                codeFacts(gold.path("codeFacts")),
                totalEvidenceItems,
                traceableEvidenceItems);
    }

    private String resolveInputText(JsonNode input) {
        JsonNode text = input.path("text");
        if (text.isTextual() && !text.asText().isBlank()) return text.asText();
        JsonNode windows = input.path("windows");
        if (windows.isArray() && windows.size() > 0) {
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
            result.add(new GoldCodeFact(item.path("repositoryId").asText(), item.path("commitSha").asText(),
                    item.path("factKey").asText(), item.path("value").asText()));
        }
        return List.copyOf(result);
    }
}