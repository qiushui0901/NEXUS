package com.example.requirementrag.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Loads and validates the versioned retrieval JSONL contract. */
public final class RetrievalEvaluationDataset {

    public static final String DEFAULT_RESOURCE = "evaluation/retrieval-eval-v1.jsonl";

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern UNSTABLE_IDENTIFIER = Pattern.compile(
            "(?i)\\\"(?:point[_-]?id|vector[_-]?id|qdrant[_-]?point[_-]?id)\\\"\\s*:");
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[A-Za-z]:/.*");

    private RetrievalEvaluationDataset() {
    }

    public static List<RetrievalEvaluationCase> loadDefault() {
        return loadResource(DEFAULT_RESOURCE);
    }

    public static List<RetrievalEvaluationCase> loadResource(String resourcePath) {
        InputStream input = RetrievalEvaluationDataset.class.getClassLoader().getResourceAsStream(resourcePath);
        if (input == null) {
            throw new DatasetValidationException("Evaluation resource not found: " + resourcePath);
        }
        try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return parse(reader);
        } catch (IOException exception) {
            throw new DatasetValidationException("Failed to read evaluation resource: " + resourcePath, exception);
        }
    }

    public static List<RetrievalEvaluationCase> parse(Reader reader) {
        ObjectMapper objectMapper = new ObjectMapper();
        List<RetrievalEvaluationCase> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (BufferedReader buffered = new BufferedReader(reader)) {
            String line;
            int lineNumber = 0;
            while ((line = buffered.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                rejectUnstableIdentifiers(line, lineNumber);
                RetrievalEvaluationCase evaluationCase;
                try {
                    evaluationCase = objectMapper.readValue(line, RetrievalEvaluationCase.class);
                } catch (Exception exception) {
                    throw invalid(lineNumber, "invalid JSON or enum value", exception);
                }
                validate(evaluationCase, lineNumber);
                if (!ids.add(evaluationCase.id())) {
                    throw invalid(lineNumber, "duplicate id: " + evaluationCase.id());
                }
                cases.add(evaluationCase);
            }
        } catch (IOException exception) {
            throw new DatasetValidationException("Failed to read evaluation JSONL", exception);
        }
        if (cases.isEmpty()) {
            throw new DatasetValidationException("Evaluation JSONL contains no cases");
        }
        return List.copyOf(cases);
    }

    private static void rejectUnstableIdentifiers(String line, int lineNumber) {
        if (UNSTABLE_IDENTIFIER.matcher(line).find()) {
            throw invalid(lineNumber, "unstable point/vector identifiers are forbidden");
        }
    }

    private static void validate(RetrievalEvaluationCase value, int lineNumber) {
        if (value == null) {
            throw invalid(lineNumber, "case must be an object");
        }
        if (isBlank(value.id()) || !ID_PATTERN.matcher(value.id()).matches()) {
            throw invalid(lineNumber, "id must be lowercase kebab-case");
        }
        if (isBlank(value.query())) {
            throw invalid(lineNumber, "query must not be blank");
        }
        if (value.profile() == null) {
            throw invalid(lineNumber, "profile is required");
        }
        if (value.expectedOutcome() == null) {
            throw invalid(lineNumber, "expectedOutcome is required");
        }
        if (isBlank(value.projectId())) {
            throw invalid(lineNumber, "projectId is required");
        }

        boolean hasGold = !value.goldDocuments().isEmpty() || !value.goldCode().isEmpty();
        if (value.expectedOutcome() == RetrievalEvaluationCase.ExpectedOutcome.HIT && !hasGold) {
            throw invalid(lineNumber, "HIT requires document or code Gold labels");
        }
        if (value.expectedOutcome() == RetrievalEvaluationCase.ExpectedOutcome.NO_RESULTS && hasGold) {
            throw invalid(lineNumber, "NO_RESULTS must not contain Gold labels");
        }

        for (int index = 0; index < value.goldDocuments().size(); index++) {
            validateDocument(value.goldDocuments().get(index), lineNumber, index);
        }
        for (int index = 0; index < value.goldCode().size(); index++) {
            validateCode(value.goldCode().get(index), lineNumber, index);
        }
        if (!value.goldDocuments().isEmpty() && (isBlank(value.documentId()) || isBlank(value.version()))) {
            throw invalid(lineNumber, "document Gold requires documentId and version");
        }
    }

    private static void validateDocument(RetrievalEvaluationCase.GoldDocument gold, int lineNumber, int index) {
        if (gold == null || isBlank(gold.filename())) {
            throw invalid(lineNumber, "goldDocuments[" + index + "].filename is required");
        }
        if (gold.parentOrder() != null && gold.parentOrder() < 0) {
            throw invalid(lineNumber, "goldDocuments[" + index + "].parentOrder must be non-negative");
        }
        if (gold.childOrder() != null && gold.childOrder() < 0) {
            throw invalid(lineNumber, "goldDocuments[" + index + "].childOrder must be non-negative");
        }
        if (gold.childOrder() != null && gold.parentOrder() == null) {
            throw invalid(lineNumber, "goldDocuments[" + index + "].childOrder requires parentOrder");
        }
        for (String fragment : gold.mustContain()) {
            if (isBlank(fragment)) {
                throw invalid(lineNumber, "goldDocuments[" + index + "].mustContain contains a blank value");
            }
        }
    }

    private static void validateCode(RetrievalEvaluationCase.GoldCode gold, int lineNumber, int index) {
        if (gold == null || isBlank(gold.projectId()) || isBlank(gold.filePath()) || isBlank(gold.symbolName())) {
            throw invalid(lineNumber, "goldCode[" + index + "] requires projectId, filePath and symbolName");
        }
        String path = normalizePath(gold.filePath());
        if (path.startsWith("/") || WINDOWS_ABSOLUTE_PATH.matcher(path).matches()
                || path.equals("..") || path.startsWith("../") || path.contains("/../")) {
            throw invalid(lineNumber, "goldCode[" + index + "].filePath must be a relative repository path");
        }
    }

    static String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/').replaceAll("/{2,}", "/");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static DatasetValidationException invalid(int lineNumber, String reason) {
        return new DatasetValidationException("Invalid evaluation JSONL at line " + lineNumber + ": " + reason);
    }

    private static DatasetValidationException invalid(int lineNumber, String reason, Exception cause) {
        return new DatasetValidationException("Invalid evaluation JSONL at line " + lineNumber + ": " + reason, cause);
    }

    public static final class DatasetValidationException extends IllegalArgumentException {
        public DatasetValidationException(String message) {
            super(message);
        }

        public DatasetValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
