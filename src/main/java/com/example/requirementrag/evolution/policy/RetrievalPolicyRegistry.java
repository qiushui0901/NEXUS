package com.example.requirementrag.evolution.policy;

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

/** 文件型检索策略注册表，支持不可变版本和原子激活引用。 */
@Repository
public class RetrievalPolicyRegistry {

    private static final Logger log = LoggerFactory.getLogger(RetrievalPolicyRegistry.class);
    private static final String ACTIVE_FILE = "active.json";

    private final ObjectMapper objectMapper;
    private final Path root;

    public RetrievalPolicyRegistry(ObjectMapper objectMapper, RagProperties properties) {
        this.objectMapper = objectMapper;
        this.root = Path.of(properties.evolution().policyRootPath()).toAbsolutePath().normalize();
    }

    public void save(RetrievalPolicy policy) {
        PolicyParameterValidator.validate(policy);
        try {
            Files.createDirectories(root);
            Path file = root.resolve(fileName(policy.policyId(), policy.version()));
            Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(policy) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("检索策略写入失败", exception);
        }
    }

    public RetrievalPolicy find(String policyId, String version) {
        Path file = root.resolve(fileName(policyId, version));
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return read(file);
    }

    public List<RetrievalPolicy> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<RetrievalPolicy> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals(ACTIVE_FILE))
                    .forEach(path -> result.add(read(path)));
        } catch (IOException exception) {
            log.warn("Unable to list retrieval policies", exception);
        }
        return List.copyOf(result);
    }

    /** 当前激活策略；不存在时返回 null。 */
    public RetrievalPolicy active() {
        String ref = readActive();
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String[] parts = ref.split("\\|", 2);
        if (parts.length != 2) {
            return null;
        }
        return find(parts[0], parts[1]);
    }

    /** 激活一个 APPROVED 策略；原子更新 active 引用。 */
    public void activate(String policyId, String version) {
        RetrievalPolicy policy = find(policyId, version);
        if (policy == null) {
            throw new IllegalArgumentException("检索策略不存在: " + policyId + " " + version);
        }
        if (policy.status() != PolicyStatus.APPROVED && policy.status() != PolicyStatus.ACTIVE) {
            throw new IllegalArgumentException("只有 APPROVED/ACTIVE 策略可以激活");
        }
        writeActive(policyId + "|" + version);
    }

    private RetrievalPolicy read(Path file) {
        try {
            return objectMapper.readValue(Files.readAllBytes(file), RetrievalPolicy.class);
        } catch (IOException exception) {
            throw new IllegalStateException("检索策略解析失败", exception);
        }
    }

    private void writeActive(String ref) {
        try {
            Files.createDirectories(root);
            Path activeFile = root.resolve(ACTIVE_FILE);
            Files.writeString(activeFile, ref + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("检索策略 active 引用写入失败", exception);
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
            log.warn("Unable to read active retrieval policy", exception);
            return null;
        }
    }

    private String fileName(String policyId, String version) {
        return safe(policyId) + "-" + safe(version) + ".json";
    }

    private String safe(String value) {
        String normalized = value == null || value.isBlank() ? "unknown" : value.trim();
        if (!normalized.matches("[A-Za-z0-9._-]{1,128}") || normalized.contains("..")) {
            throw new IllegalArgumentException("policy id/version contains unsafe characters");
        }
        return normalized;
    }
}
