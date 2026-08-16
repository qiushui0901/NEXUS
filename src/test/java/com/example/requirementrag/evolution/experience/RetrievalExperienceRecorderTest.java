package com.example.requirementrag.evolution.experience;

import com.example.requirementrag.config.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalExperienceRecorderTest {

    @TempDir
    Path tempDir;

    private RagProperties properties(boolean enabled, boolean recordingEnabled, Path root) {
        return new RagProperties(
                new RagProperties.Qdrant("http://localhost:6333", "requirement_chunks"),
                new RagProperties.Bge("http://localhost:8081", "/rerank", null),
                new RagProperties.Llm("claude-sonnet-5", "claude-sonnet-4.6", null, null, null),
                new RagProperties.Retrieval(50, 50, 40, 20, 10, true, 5_000, 6, 3, 30_000,
                        120, 1000, 900, 10000, null, null, null, null, null, 0.0),
                new RagProperties.Knowledge(false, null, null, "requirements", "5.1", null, null, 800),
                new RagProperties.Review(12, 15, 12, 3),
                new RagProperties.Code("demo", "/tmp", "code_demo", List.of(), List.of(), 1_000_000),
                List.of(),
                new RagProperties.Evolution(enabled, recordingEnabled, 1.0, 1.0, 10, true, 30,
                        root.resolve("experiences").toString(), root.resolve("candidates").toString(),
                        root.resolve("datasets").toString(), root.resolve("policies").toString()));
    }

    private RetrievalExperience experience() {
        return new RetrievalExperience(
                RetrievalExperience.SCHEMA_VERSION,
                "exp-1",
                Instant.now(),
                "demo",
                "requirements",
                "5.1",
                "hash",
                "secret query",
                "DEVELOPMENT_PLAN",
                "hybrid",
                List.of("hybrid"),
                1,
                List.of(new RetrievalExperience.HopSnapshot(0, "hybrid", "CONFIDENT", "HIT_THRESHOLD_MET", 1)),
                List.of(new RetrievalExperience.CandidateSnapshot("c1", "requirement", 1, 1, 1.0)),
                List.of("c1"),
                List.of("c1"),
                "CONFIDENT",
                "HIT_THRESHOLD_MET",
                "SUCCESS",
                List.of(),
                List.of(),
                10,
                null,
                List.of(),
                null,
                "baseline-v1",
                "cfg",
                "idx",
                null);
    }

    @Test
    void writesExperienceWhenEnabled() throws Exception {
        RetrievalExperienceRecorder recorder = new RetrievalExperienceRecorder(
                properties(true, true, tempDir), new ObjectMapper().findAndRegisterModules(), new SimpleMeterRegistry());

        recorder.recordAsync(experience());
        recorder.shutdown();

        Path root = tempDir.resolve("experiences");
        assertThat(Files.list(root)).anyMatch(path -> path.getFileName().toString().endsWith(".jsonl"));
        String content = Files.readString(Files.list(root)
                .filter(path -> path.getFileName().toString().endsWith(".jsonl")).findFirst().orElseThrow());
        assertThat(content).contains("exp-1").contains("secret query");
    }

    @Test
    void doesNotWriteWhenDisabled() throws Exception {
        RetrievalExperienceRecorder recorder = new RetrievalExperienceRecorder(
                properties(false, false, tempDir), new ObjectMapper().findAndRegisterModules(), new SimpleMeterRegistry());

        recorder.recordAsync(experience());
        recorder.shutdown();

        Path root = tempDir.resolve("experiences");
        assertThat(Files.exists(root)).isFalse();
    }
}
