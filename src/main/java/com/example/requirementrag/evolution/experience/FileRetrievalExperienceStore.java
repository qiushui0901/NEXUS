package com.example.requirementrag.evolution.experience;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 基于 JSONL 文件的经验事件存储。
 * <p>
 * 按天分文件写入，路径形如 {@code <root>/YYYY-MM-DD.jsonl}；读取时合并保留期内全部文件。
 * 写入采用追加模式，单实例部署下足够安全。
 * </p>
 */
public class FileRetrievalExperienceStore implements RetrievalExperienceStore {

    private static final Logger log = LoggerFactory.getLogger(FileRetrievalExperienceStore.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper;
    private final Path root;
    private final int retentionDays;

    public FileRetrievalExperienceStore(ObjectMapper objectMapper, Path root, int retentionDays) {
        this.objectMapper = objectMapper;
        this.root = root.toAbsolutePath().normalize();
        this.retentionDays = retentionDays;
    }

    @Override
    public boolean append(RetrievalExperience experience) {
        try {
            Files.createDirectories(root);
            Path file = root.resolve(DAY.format(experience.occurredAt()) + ".jsonl");
            String line = objectMapper.writeValueAsString(experience) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            return true;
        } catch (IOException exception) {
            log.warn("Unable to append retrieval experience; writing is isolated from retrieval", exception);
            return false;
        }
    }

    @Override
    public List<RetrievalExperience> readAll() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<RetrievalExperience> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> readFile(path, result));
        } catch (IOException exception) {
            log.warn("Unable to list retrieval experience files", exception);
        }
        return List.copyOf(result);
    }

    /** 清理超过保留期的经验文件；失败仅记录，不影响主流程。 */
    public void cleanExpired() {
        if (retentionDays <= 0 || !Files.isDirectory(root)) {
            return;
        }
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86400L);
        try (Stream<Path> files = Files.list(root)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .forEach(path -> {
                        try {
                            Instant lastModified = Files.getLastModifiedTime(path).toInstant();
                            if (lastModified.isBefore(cutoff)) {
                                Files.deleteIfExists(path);
                            }
                        } catch (IOException exception) {
                            log.warn("Unable to delete expired experience file {}", path, exception);
                        }
                    });
        } catch (IOException exception) {
            log.warn("Unable to clean expired experience files", exception);
        }
    }

    private void readFile(Path file, List<RetrievalExperience> target) {
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.filter(line -> !line.isBlank()).forEach(line -> {
                try {
                    target.add(objectMapper.readValue(line, RetrievalExperience.class));
                } catch (IOException exception) {
                    log.warn("Skip malformed experience line in {}", file, exception);
                }
            });
        } catch (IOException exception) {
            log.warn("Unable to read experience file {}", file, exception);
        }
    }
}
