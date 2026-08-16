package com.example.requirementrag.evolution.mining;

import com.example.requirementrag.config.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** 评测候选的 JSON 文件存储，每个候选一个文件。 */
@Repository
public class EvaluationCandidateStore {

    private static final Logger log = LoggerFactory.getLogger(EvaluationCandidateStore.class);

    private final ObjectMapper objectMapper;
    private final Path root;

    public EvaluationCandidateStore(ObjectMapper objectMapper, RagProperties properties) {
        this.objectMapper = objectMapper;
        this.root = Path.of(properties.evolution().candidateRootPath()).toAbsolutePath().normalize();
    }

    public void save(EvaluationCandidate candidate) {
        try {
            Files.createDirectories(root);
            Path file = root.resolve(safeId(candidate.candidateId()) + ".json");
            Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(candidate) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("评测候选写入失败", exception);
        }
    }

    public EvaluationCandidate findById(String candidateId) {
        Path file = root.resolve(safeId(candidateId) + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return read(file);
    }

    public List<EvaluationCandidate> findAll() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<EvaluationCandidate> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> result.add(read(path)));
        } catch (IOException exception) {
            log.warn("Unable to list evaluation candidates", exception);
        }
        return List.copyOf(result);
    }

    private EvaluationCandidate read(Path file) {
        try {
            return objectMapper.readValue(Files.readAllBytes(file), EvaluationCandidate.class);
        } catch (IOException exception) {
            throw new IllegalStateException("评测候选解析失败", exception);
        }
    }

    private String safeId(String id) {
        String normalized = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
        if (!normalized.matches("[A-Za-z0-9._-]{1,128}") || normalized.contains("..")) {
            throw new IllegalArgumentException("candidateId contains unsafe characters");
        }
        return normalized;
    }
}
