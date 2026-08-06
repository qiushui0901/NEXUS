package com.example.requirementrag.versioning;

import com.example.requirementrag.config.VersioningProperties;
import com.example.requirementrag.versioning.VersionModels.TestCaseSnapshot;
import com.example.requirementrag.versioning.VersionModels.TestSnapshot;
import com.example.requirementrag.versioning.VersionModels.VersionManifest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 独立于 Qdrant 持久化小巧、可审阅的版本清单。 */
@Service
public class VersionManifestService {
    private static final Pattern COMMIT = Pattern.compile("[0-9a-fA-F]{7,64}");

    private final ObjectMapper objectMapper;
    private final Path root;

    public VersionManifestService(ObjectMapper objectMapper, VersioningProperties properties) {
        this.objectMapper = objectMapper;
        this.root = Path.of(properties.rootPath()).toAbsolutePath().normalize();
    }

    /**
     * 校验并规范化清单各字段后原子落盘；文件已存在时保留原 createdAt。
     *
     * @param input 待保存清单
     * @return 规范化后的清单
     */
    public VersionManifest save(VersionManifest input) {
        if (input == null) throw new IllegalArgumentException("版本档案不能为空");
        String projectId = VersionPathPolicy.identifier(input.projectId(), "projectId");
        String version = VersionPathPolicy.identifier(input.version(), "version");
        String now = Instant.now().toString();
        Path file = manifestPath(projectId, version);
        String createdAt = Files.isRegularFile(file) ? read(file).createdAt() : now;
        VersionManifest manifest = new VersionManifest(
                1,
                projectId,
                version,
                optionalIdentifier(input.baseVersion(), "baseVersion"),
                optionalIdentifier(input.requirementDocumentId(), "requirementDocumentId"),
                optionalIdentifier(input.requirementVersion(), "requirementVersion"),
                commit(input.baseCodeCommit(), "baseCodeCommit"),
                commit(input.codeCommit(), "codeCommit"),
                validateSnapshot(input.testSnapshot()),
                optionalIdentifier(input.wikiVersion(), "wikiVersion"),
                optionalText(input.wikiBuildId(), 200, "wikiBuildId"),
                input.status() == null ? VersionModels.ManifestStatus.DRAFT : input.status(),
                hasText(createdAt) ? createdAt : now,
                now,
                cleanNotes(input.notes()));
        writeAtomically(file, manifest);
        return manifest;
    }

    /**
     * 按项目与版本读取清单。
     *
     * @param projectId 项目标识
     * @param version   版本号
     * @return 清单，文件不存在时为空
     */
    public Optional<VersionManifest> find(String projectId, String version) {
        Path file = manifestPath(
                VersionPathPolicy.identifier(projectId, "projectId"),
                VersionPathPolicy.identifier(version, "version"));
        return Files.isRegularFile(file) ? Optional.of(read(file)) : Optional.empty();
    }

    /**
     * 按项目与版本读取清单，不存在时返回 404。
     *
     * @param projectId 项目标识
     * @param version   版本号
     * @return 清单
     */
    public VersionManifest get(String projectId, String version) {
        Path file = manifestPath(
                VersionPathPolicy.identifier(projectId, "projectId"),
                VersionPathPolicy.identifier(version, "version"));
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本档案不存在");
        }
        return read(file);
    }

    /**
     * 列出项目下全部清单，按版本号降序排序。
     *
     * @param projectId 项目标识
     * @return 清单列表
     */
    public List<VersionManifest> list(String projectId) {
        String safeProject = VersionPathPolicy.identifier(projectId, "projectId");
        Path projectRoot = VersionPathPolicy.resolveBelow(root, safeProject);
        if (!Files.isDirectory(projectRoot)) return List.of();
        try (Stream<Path> files = Files.list(projectRoot)) {
            return files.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .map(this::read)
                    .sorted(Comparator.comparing(VersionManifest::version, versionComparator()).reversed())
                    .toList();
        } catch (IOException exception) {
            throw unavailable("读取版本档案列表失败", exception);
        }
    }

    Path root() {
        return root;
    }

    private Path manifestPath(String projectId, String version) {
        return VersionPathPolicy.resolveBelow(root, projectId).resolve(version + ".json").normalize();
    }

    private VersionManifest read(Path file) {
        try {
            return objectMapper.readValue(Files.readAllBytes(file), VersionManifest.class);
        } catch (IOException exception) {
            throw unavailable("读取版本档案失败", exception);
        }
    }

    /** 先写临时文件再原子替换目标文件，失败时清理临时文件。 */
    private void writeAtomically(Path file, VersionManifest manifest) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), ".manifest-", ".tmp");
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), manifest);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw unavailable("保存版本档案失败", exception);
        }
    }

    /** 校验测试统计非负且不超总数，去重并规范化测试用例。 */
    private TestSnapshot validateSnapshot(TestSnapshot snapshot) {
        if (snapshot == null) return null;
        if (snapshot.total() < 0 || snapshot.passed() < 0 || snapshot.failed() < 0 || snapshot.skipped() < 0) {
            throw new IllegalArgumentException("测试统计不能为负数");
        }
        if (snapshot.passed() + snapshot.failed() + snapshot.skipped() > snapshot.total()) {
            throw new IllegalArgumentException("测试统计超过总用例数");
        }
        List<TestCaseSnapshot> cases = new ArrayList<>();
        Set<String> caseIds = new HashSet<>();
        for (TestCaseSnapshot item : snapshot.cases()) {
            if (item == null || !hasText(item.caseId())) throw new IllegalArgumentException("测试用例 caseId 不能为空");
            String caseId = item.caseId().trim();
            if (!caseIds.add(caseId)) throw new IllegalArgumentException("测试用例 caseId 重复");
            cases.add(new TestCaseSnapshot(caseId, optionalText(item.name(), 300, "测试用例名称"),
                    item.status() == null ? VersionModels.TestCaseStatus.NOT_RUN : item.status()));
        }
        return new TestSnapshot(optionalText(snapshot.reportId(), 200, "reportId"),
                snapshot.status() == null ? VersionModels.TestRunStatus.NOT_RUN : snapshot.status(),
                snapshot.total(), snapshot.passed(), snapshot.failed(), snapshot.skipped(), cases);
    }

    private String optionalIdentifier(String value, String field) {
        return hasText(value) ? VersionPathPolicy.identifier(value, field) : null;
    }

    /** 校验并规范化 Git commit SHA（7-64 位十六进制），统一小写；空值返回 null。 */
    private String commit(String value, String field) {
        if (!hasText(value)) return null;
        String normalized = value.trim();
        if (!COMMIT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " 必须是具体的 Git commit SHA");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private List<String> cleanNotes(List<String> notes) {
        if (notes == null) return List.of();
        return notes.stream().filter(VersionManifestService::hasText)
                .map(value -> optionalText(value, 500, "notes"))
                .toList();
    }

    private static String optionalText(String value, int maxLength, String field) {
        if (!hasText(value)) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(field + " 过长");
        return normalized;
    }

    /** 语义化版本号比较器：逐段按整数比较，非数字段按字典序兜底。 */
    static Comparator<String> versionComparator() {
        return (left, right) -> {
            String[] a = left.split("\\.");
            String[] b = right.split("\\.");
            for (int index = 0; index < Math.max(a.length, b.length); index++) {
                int comparison = Integer.compare(versionPart(a, index), versionPart(b, index));
                if (comparison != 0) return comparison;
            }
            return left.compareTo(right);
        };
    }

    private static int versionPart(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try { return Integer.parseInt(parts[index]); }
        catch (NumberFormatException exception) { return -1; }
    }

    private ResponseStatusException unavailable(String message, Exception cause) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message, cause);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
