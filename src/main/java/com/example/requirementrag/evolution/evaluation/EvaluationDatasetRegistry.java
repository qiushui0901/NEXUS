package com.example.requirementrag.evolution.evaluation;

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
import java.util.List;
import java.util.stream.Stream;

/** 不可变评测数据集注册表，基于文件系统存储。 */
@Repository
public class EvaluationDatasetRegistry {

    private static final Logger log = LoggerFactory.getLogger(EvaluationDatasetRegistry.class);
    private static final String ACTIVE_FILE = "active.json";

    private final ObjectMapper objectMapper;
    private final Path root;

    public EvaluationDatasetRegistry(ObjectMapper objectMapper, RagProperties properties) {
        this.objectMapper = objectMapper;
        this.root = Path.of(properties.evolution().datasetRootPath()).toAbsolutePath().normalize();
    }

    /** 发布一个不可变数据集版本并原子更新 active 引用。 */
    public void publish(EvaluationDataset dataset) {
        try {
            Files.createDirectories(root);
            Path file = root.resolve(safeVersion(dataset.version()) + ".json");
            Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(dataset) + System.lineSeparator(), StandardCharsets.UTF_8);
            writeActive(dataset.version());
        } catch (IOException exception) {
            throw new IllegalStateException("评测数据集发布失败", exception);
        }
    }

    public EvaluationDataset find(String version) {
        Path file = root.resolve(safeVersion(version) + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return read(file);
    }

    public EvaluationDataset active() {
        String version = readActive();
        return version == null ? null : find(version);
    }

    /** 回滚 active 到指定版本（必须是已发布版本），并更新 active 引用。 */
    public EvaluationDataset rollback(String version) {
        EvaluationDataset dataset = find(version);
        if (dataset == null) {
            throw new IllegalArgumentException("数据集版本不存在: " + version);
        }
        writeActive(version);
        return dataset;
    }

    public List<EvaluationDataset> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<EvaluationDataset> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals(ACTIVE_FILE))
                    .forEach(path -> result.add(read(path)));
        } catch (IOException exception) {
            log.warn("Unable to list evaluation datasets", exception);
        }
        return List.copyOf(result);
    }

    private EvaluationDataset read(Path file) {
        try {
            return objectMapper.readValue(Files.readAllBytes(file), EvaluationDataset.class);
        } catch (IOException exception) {
            throw new IllegalStateException("评测数据集解析失败", exception);
        }
    }

    private void writeActive(String version) {
        try {
            Path activeFile = root.resolve(ACTIVE_FILE);
            Files.writeString(activeFile, version + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("评测数据集 active 引用写入失败", exception);
        }
    }

    private String readActive() {
        Path activeFile = root.resolve(ACTIVE_FILE);
        if (!Files.isRegularFile(activeFile)) {
            return null;
        }
        try {
            String value = Files.readString(activeFile, StandardCharsets.UTF_8).trim();
            return value.isBlank() ? null : value;
        } catch (IOException exception) {
            log.warn("Unable to read active dataset version", exception);
            return null;
        }
    }

    private String safeVersion(String version) {
        String normalized = version == null || version.isBlank() ? "unknown" : version.trim();
        if (!normalized.matches("[A-Za-z0-9._-]{1,128}") || normalized.contains("..")) {
            throw new IllegalArgumentException("dataset version contains unsafe characters");
        }
        return normalized;
    }
}
