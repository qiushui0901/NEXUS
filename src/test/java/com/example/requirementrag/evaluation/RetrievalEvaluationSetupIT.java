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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

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
        SetupConfiguration configuration = configuration();
        List<FixtureContent> fixtures = loadFixtures(configuration.fixture());
        List<KnowledgeEntry> entries = fixtures.stream()
                .map(value -> new KnowledgeEntry(
                        value.filename(), new String(value.bytes(), StandardCharsets.UTF_8)))
                .toList();
        String requirementCollection = projectRegistry.resolveRequirementCollection(configuration.projectId());

        IngestResponse requirementIndex = requirementIngestionService.ingestEntries(
                requirementCollection, configuration.documentId(), configuration.version(), entries);
        CodeIndexResponse codeIndex = configuration.skipCodeIndex()
                ? null
                : codeKnowledgeService.index(configuration.projectId());

        long persistedRequirementChunks = requirementStore.countVersion(
                requirementCollection, configuration.documentId(), configuration.version());
        String codeCollection = configuration.skipCodeIndex()
                ? null
                : projectRegistry.resolveCodeCollection(configuration.projectId());
        long persistedCodeChunks = configuration.skipCodeIndex()
                ? 0
                : codeStore.countProject(codeCollection, configuration.projectId());
        List<ChunkRecord> persistedRequirementCorpus = requirementStore.scrollVersion(
                requirementCollection, configuration.documentId(), configuration.version());

        assertEquals(requirementIndex.chunks(), persistedRequirementChunks);
        assertFalse(persistedRequirementCorpus.isEmpty());
        if (configuration.fixture().equals(FIXTURE)) {
            assertTrue(persistedRequirementCorpus.stream()
                    .anyMatch(chunk -> chunk.parentText().contains(LAST_FROZEN_ANCHOR)),
                    "persisted requirement corpus does not contain the final frozen anchor");
        }
        if (!configuration.skipCodeIndex()) {
            assertEquals(codeIndex.chunks(), persistedCodeChunks);
            assertEquals(requiredEnvironment("SHIGUANG_COMMIT_FIXED"), codeIndex.commitSha());
        }

        Path output = Path.of(System.getenv().getOrDefault(
                "RETRIEVAL_EVAL_SETUP_OUTPUT", DEFAULT_OUTPUT.toString()));
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        SetupManifest manifest = new SetupManifest(
                1,
                Instant.now().toString(),
                configuration.projectId(),
                configuration.documentId(),
                configuration.version(),
                configuration.fixture().toString(),
                fixtureDigest(fixtures),
                fixtures.stream().mapToLong(value -> value.bytes().length).sum(),
                fixtureFingerprints(fixtures),
                requirementCollection,
                requirementIndex.chunks(),
                persistedRequirementChunks,
                codeCollection,
                codeIndex == null ? null : codeIndex.commitSha(),
                codeIndex == null ? 0 : codeIndex.files(),
                codeIndex == null ? 0 : codeIndex.chunks(),
                persistedCodeChunks);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), manifest);
        System.out.printf("Frozen evaluation corpus rebuilt: files=%d, requirements=%d, code=%d, manifest=%s%n",
                fixtures.size(), persistedRequirementChunks, persistedCodeChunks, output.toAbsolutePath());
    }

    private SetupConfiguration configuration() {
        return new SetupConfiguration(
                environment("RETRIEVAL_EVAL_SETUP_PROJECT_ID", PROJECT_ID),
                environment("RETRIEVAL_EVAL_SETUP_DOCUMENT_ID", DOCUMENT_ID),
                environment("RETRIEVAL_EVAL_SETUP_VERSION", VERSION),
                Path.of(environment("RETRIEVAL_EVAL_SETUP_FIXTURE", FIXTURE.toString())),
                Boolean.parseBoolean(environment("RETRIEVAL_EVAL_SETUP_SKIP_CODE", "false")));
    }

    private List<FixtureContent> loadFixtures(Path fixture) throws Exception {
        if (Files.isRegularFile(fixture)) {
            return List.of(new FixtureContent(fixture.getFileName().toString(), Files.readAllBytes(fixture)));
        }
        if (!Files.isDirectory(fixture)) {
            throw new IllegalArgumentException("Evaluation fixture does not exist: " + fixture);
        }
        List<Path> files;
        try (Stream<Path> values = Files.list(fixture)) {
            files = values.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("Evaluation fixture directory contains no Markdown files: " + fixture);
        }
        List<FixtureContent> contents = new ArrayList<>();
        for (Path file : files) {
            contents.add(new FixtureContent(file.getFileName().toString(), Files.readAllBytes(file)));
        }
        return List.copyOf(contents);
    }

    private String fixtureDigest(List<FixtureContent> fixtures) throws Exception {
        if (fixtures.size() == 1) {
            return sha256(fixtures.getFirst().bytes());
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (FixtureContent fixture : fixtures) {
            digest.update(fixture.filename().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(fixture.bytes());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private List<FixtureFingerprint> fixtureFingerprints(List<FixtureContent> fixtures) throws Exception {
        List<FixtureFingerprint> fingerprints = new ArrayList<>();
        for (FixtureContent fixture : fixtures) {
            fingerprints.add(new FixtureFingerprint(
                    fixture.filename(), sha256(fixture.bytes()), fixture.bytes().length));
        }
        return List.copyOf(fingerprints);
    }

    private String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
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
            List<FixtureFingerprint> requirementFixtures,
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

    record FixtureFingerprint(String filename, String sha256, long bytes) {
    }

    record FixtureContent(String filename, byte[] bytes) {
        FixtureContent {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    record SetupConfiguration(
            String projectId,
            String documentId,
            String version,
            Path fixture,
            boolean skipCodeIndex
    ) {
    }
}
