package com.example.requirementrag.evaluation;

import com.example.requirementrag.service.ParentChildChunker;
import com.example.requirementrag.service.TextPreprocessor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalEvaluationV2CorpusTest {

    private static final String DATASET = "evaluation/retrieval-eval-document-v2.jsonl";
    private static final String CORPUS_ROOT = "evaluation/document-v2/";
    private static final String MANIFEST = CORPUS_ROOT + "manifest.json";
    private static final int GOLD_FILES = 6;
    private static final int CORPUS_FILES = 18;
    private static final int HARD_NEGATIVE_FILES = CORPUS_FILES - GOLD_FILES;

    @Test
    void frozenV2CorpusKeepsStructuredGoldAtUniqueStablePositions() throws Exception {
        List<RetrievalEvaluationCase> cases = RetrievalEvaluationDataset.loadResource(DATASET);
        Set<String> goldFilenames = cases.stream()
                .flatMap(value -> value.goldDocuments().stream())
                .map(RetrievalEvaluationCase.GoldDocument::filename)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> corpusFilenames = manifestFilenames();

        assertEquals(24, cases.size());
        assertEquals(GOLD_FILES, goldFilenames.size());
        assertEquals(CORPUS_FILES, corpusFilenames.size());
        assertTrue(corpusFilenames.containsAll(goldFilenames));
        assertTrue(cases.stream().allMatch(value -> "document-v2-v2".equals(value.version())));
        assertTrue(cases.stream().allMatch(value -> value.tags().contains("hard-negative")));
        assertTrue(cases.stream().allMatch(value -> value.tags().contains("structured-gold")));

        Map<String, List<ParentChildChunker.ParentChunk>> corpus = loadCorpus(corpusFilenames);
        for (RetrievalEvaluationCase evaluationCase : cases) {
            assertEquals(1, evaluationCase.goldDocuments().size(), evaluationCase.id());
            RetrievalEvaluationCase.GoldDocument gold = evaluationCase.goldDocuments().getFirst();
            List<ParentChildChunker.ParentChunk> parents = corpus.get(gold.filename());
            assertTrue(gold.parentOrder() < parents.size(), evaluationCase.id());
            ParentChildChunker.ParentChunk parent = parents.get(gold.parentOrder());
            assertTrue(gold.childOrder() < parent.children().size(), evaluationCase.id());
            String child = parent.children().get(gold.childOrder());
            assertTrue(gold.mustContain().stream().allMatch(child::contains), evaluationCase.id());
            assertEquals(1, matchingChildren(corpus, gold.mustContain()), evaluationCase.id());
        }
    }

    @Test
    void manifestFingerprintsDatasetAndEveryCorpusFile() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode manifest = mapper.readTree(new ByteArrayInputStream(resourceBytes(MANIFEST)));

        assertEquals(2, manifest.path("schemaVersion").asInt());
        assertEquals("document-v2-v2", manifest.path("datasetVersion").asText());
        assertEquals("document-v2-v2", manifest.path("version").asText());
        assertEquals(CORPUS_FILES, manifest.path("fileCount").asInt());
        assertEquals(GOLD_FILES, manifest.path("goldFileCount").asInt());
        assertEquals(HARD_NEGATIVE_FILES, manifest.path("hardNegativeFileCount").asInt());
        assertEquals(24, manifest.path("structuredHitCases").asInt());
        assertEquals(sha256(resourceBytes(DATASET)), manifest.path("datasetSha256").asText());
        assertEquals(CORPUS_FILES, manifest.path("files").size());
        Set<String> sourceFiles;
        try (var files = Files.list(Path.of("src/test/resources/evaluation/document-v2"))) {
            sourceFiles = files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        assertEquals(sourceFiles, manifestFilenames());
        Set<String> declaredGoldFiles = java.util.stream.StreamSupport.stream(
                        manifest.path("files").spliterator(), false)
                .filter(file -> "gold".equals(file.path("role").asText()))
                .map(file -> file.path("filename").asText())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertEquals(GOLD_FILES, declaredGoldFiles.size());
        int hardNegativeFiles = 0;
        TextPreprocessor preprocessor = new TextPreprocessor();
        ParentChildChunker chunker = new ParentChildChunker();
        for (JsonNode file : manifest.path("files")) {
            String filename = file.path("filename").asText();
            byte[] bytes = resourceBytes(CORPUS_ROOT + filename);
            String cleaned = preprocessor.clean(new String(bytes, StandardCharsets.UTF_8));
            List<ParentChildChunker.ParentChunk> parents = chunker.split(cleaned);
            assertEquals(bytes.length, file.path("bytes").asLong(), filename);
            assertEquals(sha256(bytes), file.path("sha256").asText(), filename);
            assertEquals(cleaned.length(), file.path("cleanedCharacters").asInt(), filename);
            assertEquals(parents.size(), file.path("parentCount").asInt(), filename);
            assertEquals(parents.stream().map(parent -> parent.children().size()).toList(),
                    mapper.treeToValue(file.path("childCounts"), List.class), filename);
            assertTrue(parents.size() >= 2, filename);
            if ("hard-negative".equals(file.path("role").asText())) {
                hardNegativeFiles++;
                assertTrue(file.path("hardNegativeFor").isArray(), filename);
                assertTrue(!file.path("hardNegativeFor").isEmpty(), filename);
                assertTrue(java.util.stream.StreamSupport.stream(
                                file.path("hardNegativeFor").spliterator(), false)
                        .map(JsonNode::asText)
                        .allMatch(declaredGoldFiles::contains), filename);
            } else {
                assertEquals("gold", file.path("role").asText(), filename);
            }
        }
        assertEquals(HARD_NEGATIVE_FILES, hardNegativeFiles);
    }

    private Set<String> manifestFilenames() throws IOException {
        JsonNode manifest = new ObjectMapper().readTree(new ByteArrayInputStream(resourceBytes(MANIFEST)));
        return java.util.stream.StreamSupport.stream(manifest.path("files").spliterator(), false)
                .map(file -> file.path("filename").asText())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Map<String, List<ParentChildChunker.ParentChunk>> loadCorpus(Set<String> filenames)
            throws IOException {
        TextPreprocessor preprocessor = new TextPreprocessor();
        ParentChildChunker chunker = new ParentChildChunker();
        Map<String, List<ParentChildChunker.ParentChunk>> corpus = new LinkedHashMap<>();
        for (String filename : filenames.stream().sorted().toList()) {
            String raw = new String(resourceBytes(CORPUS_ROOT + filename), StandardCharsets.UTF_8);
            corpus.put(filename, chunker.split(preprocessor.clean(raw)));
        }
        return Map.copyOf(corpus);
    }

    private int matchingChildren(
            Map<String, List<ParentChildChunker.ParentChunk>> corpus,
            List<String> fragments) {
        int matches = 0;
        for (List<ParentChildChunker.ParentChunk> parents : corpus.values()) {
            for (ParentChildChunker.ParentChunk parent : parents) {
                for (String child : parent.children()) {
                    if (fragments.stream().allMatch(child::contains)) {
                        matches++;
                    }
                }
            }
        }
        return matches;
    }

    private byte[] resourceBytes(String resource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing classpath resource: " + resource);
            }
            return input.readAllBytes();
        }
    }

    private String sha256(byte[] value) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
