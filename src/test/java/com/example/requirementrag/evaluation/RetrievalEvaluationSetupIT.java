package com.example.requirementrag.evaluation;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.code.CodeQdrantStore;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeIndexResponse;
import com.example.requirementrag.model.IngestResponse;
import com.example.requirementrag.model.KnowledgeEntry;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.service.RequirementIngestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rebuilds and fingerprints the frozen requirement/code corpus before a formal retrieval evaluation. */
@SpringBootTest(properties = {
        "logging.structured.format.console=",
        "management.tracing.sampling.probability=0",
        "app.rag.knowledge.bootstrap-enabled=false",
        "app.rag.auth.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "RUN_RETRIEVAL_EVAL_SETUP", matches = "(?i)true")
class RetrievalEvaluationSetupIT {

    private static final String PROJECT_ID = "shiguang-eval";
    private static final String DOCUMENT_ID = "shiguang-eval-requirements";
    private static final String VERSION = "shiguang-eval-v1";
    private static final String FIXTURE_NAME = "shiguang-eval-requirements.md";
    private static final Path FIXTURE = Path.of("evaluation", "shiguang", FIXTURE_NAME);
    private static final Path DEFAULT_OUTPUT = Path.of("target", "retrieval-evaluation", "setup.json");
    private static final String LAST_FROZEN_ANCHOR = "项目检索只返回所属 collection 的证据";

    @Autowired
    private RequirementIngestionService requirementIngestionService;

    @Autowired
    private CodeKnowledgeService codeKnowledgeService;

    @Autowired
    private QdrantHybridStore requirementStore;

    @Autowired
    private CodeQdrantStore codeStore;

    @Autowired
    private ProjectRegistry projectRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void rebuildsAndFingerprintsFrozenEvaluationCorpus() throws Exception {
        byte[] fixtureBytes = Files.readAllBytes(FIXTURE);
        String fixtureText = new String(fixtureBytes, StandardCharsets.UTF_8);
        String requirementCollection = projectRegistry.resolveRequirementCollection(PROJECT_ID);
        String codeCollection = projectRegistry.resolveCodeCollection(PROJECT_ID);

        IngestResponse requirementIndex = requirementIngestionService.ingestEntries(
                requirementCollection, DOCUMENT_ID, VERSION,
                List.of(new KnowledgeEntry(FIXTURE_NAME, fixtureText)));
        CodeIndexResponse codeIndex = codeKnowledgeService.index(PROJECT_ID);

        long persistedRequirementChunks = requirementStore.countVersion(
                requirementCollection, DOCUMENT_ID, VERSION);
        long persistedCodeChunks = codeStore.countProject(codeCollection, PROJECT_ID);
        List<ChunkRecord> persistedRequirementCorpus = requirementStore.scrollVersion(
                requirementCollection, DOCUMENT_ID, VERSION);

        assertEquals(requirementIndex.chunks(), persistedRequirementChunks);
        assertEquals(codeIndex.chunks(), persistedCodeChunks);
        assertFalse(persistedRequirementCorpus.isEmpty());
        assertTrue(persistedRequirementCorpus.stream()
                .anyMatch(chunk -> chunk.parentText().contains(LAST_FROZEN_ANCHOR)),
                "persisted requirement corpus does not contain the final frozen anchor");
        assertEquals(requiredEnvironment("SHIGUANG_COMMIT_FIXED"), codeIndex.commitSha());

        Path output = Path.of(System.getenv().getOrDefault(
                "RETRIEVAL_EVAL_SETUP_OUTPUT", DEFAULT_OUTPUT.toString()));
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        SetupManifest manifest = new SetupManifest(
                1,
                Instant.now().toString(),
                PROJECT_ID,
                DOCUMENT_ID,
                VERSION,
                FIXTURE.toString(),
                sha256(fixtureBytes),
                fixtureBytes.length,
                requirementCollection,
                requirementIndex.chunks(),
                persistedRequirementChunks,
                codeCollection,
                codeIndex.commitSha(),
                codeIndex.files(),
                codeIndex.chunks(),
                persistedCodeChunks);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), manifest);
        System.out.printf("Frozen evaluation corpus rebuilt: requirements=%d, code=%d, manifest=%s%n",
                persistedRequirementChunks, persistedCodeChunks, output.toAbsolutePath());
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    record SetupManifest(
            int schemaVersion,
            String generatedAt,
            String projectId,
            String documentId,
            String version,
            String requirementFixture,
            String requirementFixtureSha256,
            long requirementFixtureBytes,
            String requirementCollection,
            int requirementChunksWritten,
            long requirementChunksPersisted,
            String codeCollection,
            String codeCommit,
            int codeFilesScanned,
            int codeChunksWritten,
            long codeChunksPersisted
    ) {
    }
}
