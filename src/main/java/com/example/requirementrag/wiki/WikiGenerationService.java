package com.example.requirementrag.wiki;

import com.example.requirementrag.config.WikiProperties;
import com.example.requirementrag.wiki.WikiModels.CodeEntry;
import com.example.requirementrag.wiki.WikiModels.Evidence;
import com.example.requirementrag.wiki.WikiModels.KnowledgeQuality;
import com.example.requirementrag.wiki.WikiModels.GenerationResult;
import com.example.requirementrag.wiki.WikiModels.Page;
import com.example.requirementrag.wiki.WikiModels.PageSource;
import com.example.requirementrag.wiki.WikiModels.PageSummary;
import com.example.requirementrag.wiki.WikiModels.RequirementSource;
import com.example.requirementrag.wiki.WikiModels.TestKnowledge;
import com.example.requirementrag.wiki.WikiModels.VersionChange;
import com.example.requirementrag.wiki.WikiModels.VersionIndex;
import com.example.requirementrag.wiki.WikiModels.VersionSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 校验版本化事实，并发布人读 Markdown 与 JSON 产物。 */
@Service
public class WikiGenerationService {
    private static final Logger log = LoggerFactory.getLogger(WikiGenerationService.class);
    private static final Pattern FORBIDDEN_SOURCE_FIELD = Pattern.compile(
            "(?i)\\\"(?:vector|vectors|denseVector|sparseVector|embedding|embeddings|qdrantPoint|qdrantPoints|"
                    + "snapshot|snapshots|storage|apiKey|password|secret|token|authorization|credential|credentials)"
                    + "\\\"\\s*:");

    private final ObjectMapper objectMapper;
    private final WikiRepository repository;
    private final Path sourceRoot;
    private final Object publishLock = new Object();

    public WikiGenerationService(ObjectMapper objectMapper, WikiProperties properties, WikiRepository repository) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.sourceRoot = Path.of(properties.sourcePath()).toAbsolutePath().normalize();
    }

    /**
     * 读取并校验 Wiki 源定义后，生成各页面的 JSON/Markdown 与版本索引，
     * 再原子发布到项目/版本目录，并失效对应缓存。
     *
     * @param projectId 项目标识
     * @param version   版本号
     * @return 生成结果（页数、输出路径与生成时间）
     */
    public GenerationResult generate(String projectId, String version) {
        String safeProject = WikiPathPolicy.identifier(projectId, "projectId");
        String safeVersion = WikiPathPolicy.identifier(version, "version");
        Path sourceFile = sourceRoot.resolve(safeProject + "-v" + safeVersion + ".json").normalize();
        if (!sourceFile.startsWith(sourceRoot) || !Files.isRegularFile(sourceFile)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Wiki 源定义不存在: " + safeProject + " " + safeVersion);
        }

        VersionSource source;
        try {
            String sourceJson = Files.readString(sourceFile, StandardCharsets.UTF_8);
            if (FORBIDDEN_SOURCE_FIELD.matcher(sourceJson).find()) {
                throw new IllegalArgumentException("Wiki 源定义不得包含向量、Qdrant 运行数据或凭据字段");
            }
            source = objectMapper.readValue(sourceJson, VersionSource.class);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Wiki 源定义无法解析", exception);
        }
        validate(source, safeProject, safeVersion);

        String generatedAt = text(source.generatedAt()).isBlank() ? Instant.now().toString() : source.generatedAt().trim();
        Path projectRoot = WikiPathPolicy.resolveBelow(repository.root(), safeProject);
        Path target = WikiPathPolicy.resolveBelow(repository.root(), safeProject, safeVersion);
        Path staging = projectRoot.resolve("." + safeVersion + ".next-" + UUID.randomUUID()).normalize();
        try {
            Files.createDirectories(staging.resolve("pages"));
            List<PageSummary> summaries = new ArrayList<>();
            for (PageSource pageSource : source.pages()) {
                Page page = toPage(source, pageSource, generatedAt);
                writeJson(staging.resolve("pages").resolve(page.featureId() + ".json"), page);
                Files.writeString(staging.resolve("pages").resolve(page.featureId() + ".md"),
                        renderMarkdown(page), StandardCharsets.UTF_8);
                summaries.add(new PageSummary(page.featureId(), page.title(), page.category(),
                        page.introducedVersion(), page.status(), page.summary(), page.aliases(), page.evidence().size()));
            }
            summaries.sort(java.util.Comparator.comparing(PageSummary::category).thenComparing(PageSummary::title));
            VersionIndex index = new VersionIndex(source.schemaVersion(), source.projectId(), source.projectName(),
                    source.version(), source.requirementVersion(), source.baseCodeCommit(), source.codeCommit(),
                    generatedAt, List.copyOf(summaries));
            writeJson(staging.resolve("index.json"), index);
            publish(staging, target);
            repository.invalidate(safeProject, safeVersion);
            return new GenerationResult(safeProject, safeVersion, summaries.size(),
                    target.toString(), generatedAt);
        } catch (IOException exception) {
            deleteQuietly(staging);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "生成 Wiki 失败", exception);
        }
    }

    /** 校验源定义整体及每个页面的必填字段、重复 featureId、关联引用完整性等约束。 */
    private void validate(VersionSource source, String projectId, String version) {
        if (source == null) throw new IllegalArgumentException("Wiki 源定义不能为空");
        if (source.schemaVersion() < 1 || source.schemaVersion() > 2) throw new IllegalArgumentException("不支持的 Wiki schemaVersion");
        if (!projectId.equals(WikiPathPolicy.identifier(source.projectId(), "source.projectId"))) {
            throw new IllegalArgumentException("源定义 projectId 与请求不一致");
        }
        if (!version.equals(WikiPathPolicy.identifier(source.version(), "source.version"))) {
            throw new IllegalArgumentException("源定义 version 与请求不一致");
        }
        if (source.pages() == null || source.pages().isEmpty()) {
            throw new IllegalArgumentException("Wiki 至少需要一个页面");
        }
        Set<String> ids = new HashSet<>();
        for (PageSource page : source.pages()) {
            if (page == null) throw new IllegalArgumentException("Wiki 页面不能为空");
            String featureId = WikiPathPolicy.identifier(page.featureId(), "featureId");
            if (!ids.add(featureId)) throw new IllegalArgumentException("重复 featureId: " + featureId);
            if (text(page.title()).isBlank()) throw new IllegalArgumentException(featureId + " 缺少标题");
            if (page.status() == null) throw new IllegalArgumentException(featureId + " 缺少状态");
            if (!text(page.introducedVersion()).isBlank()) {
                WikiPathPolicy.identifier(page.introducedVersion(), featureId + ".introducedVersion");
            }
            validateTextList(featureId, "aliases", page.aliases());
            validateTextList(featureId, "productRules", page.productRules());
            validateTextList(featureId, "processSteps", page.processSteps());
            validateTextList(featureId, "codeSymbols", page.codeSymbols());
            validateTextList(featureId, "dataImpacts", page.dataImpacts());
            validateTextList(featureId, "boundaryConditions", page.boundaryConditions());
            validateTextList(featureId, "acceptanceCriteria", page.acceptanceCriteria());
            validateTextList(featureId, "testPoints", page.testPoints());
            validateTextList(featureId, "risks", page.risks());
            validateObjects(featureId, "requirementSources", page.requirementSources());
            validateObjects(featureId, "codeEntries", page.codeEntries());
            if (page.testKnowledge() != null) validateTextList(featureId, "testKnowledge.cases", page.testKnowledge().cases());
            if (page.quality() != null) validateTextList(featureId, "quality.missing", page.quality().missing());
            for (WikiModels.Relation relation : list(page.relations())) {
                if (relation == null) throw new IllegalArgumentException(featureId + " 包含空关联");
                WikiPathPolicy.identifier(relation.targetFeatureId(), "relation.targetFeatureId");
            }
            for (Evidence evidence : list(page.evidence())) {
                if (evidence == null) throw new IllegalArgumentException(featureId + " 包含空证据");
            }
        }
        for (PageSource page : source.pages()) {
            for (WikiModels.Relation relation : list(page.relations())) {
                if (!ids.contains(relation.targetFeatureId())) {
                    throw new IllegalArgumentException(page.featureId() + " 关联了不存在的 featureId: "
                            + relation.targetFeatureId());
                }
            }
        }
    }

    private void validateTextList(String featureId, String field, List<String> values) {
        if (values != null && values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(featureId + "." + field + " 不能包含 null");
        }
    }

    private void validateObjects(String featureId, String field, List<?> values) {
        if (values != null && values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(featureId + "." + field + " 不能包含 null");
        }
    }

    /** 将页面源数据补齐默认值（分类、引入版本、测试知识、版本变化、质量评估）并转为页面模型。 */
    private Page toPage(VersionSource source, PageSource page, String generatedAt) {
        String featureId = WikiPathPolicy.identifier(page.featureId(), "featureId");
        return new Page(
                source.projectId(), text(source.projectName()), source.version(), text(source.requirementVersion()),
                text(source.baseCodeCommit()), text(source.codeCommit()), generatedAt,
                featureId, text(page.title()), fallback(page.category(), "未分类"),
                fallback(page.introducedVersion(), source.version()), page.status(),
                list(page.aliases()), text(page.summary()), list(page.requirementSources()),
                list(page.productRules()), list(page.processSteps()), list(page.codeEntries()), list(page.codeSymbols()),
                list(page.dataImpacts()), list(page.boundaryConditions()), list(page.acceptanceCriteria()),
                list(page.testPoints()), testKnowledge(page.testKnowledge()),
                versionChange(page.versionChange(), source.version()),
                quality(page.quality(), page.requirementSources(), page.codeEntries()),
                list(page.risks()), list(page.relations()), list(page.evidence()),
                "pages/" + featureId + ".md");
    }

    /** 将页面模型渲染为带 YAML 元头的 Markdown 文档。 */
    private String renderMarkdown(Page page) {
        StringBuilder out = new StringBuilder();
        out.append("---\n")
                .append("featureId: ").append(yaml(page.featureId())).append('\n')
                .append("projectId: ").append(yaml(page.projectId())).append('\n')
                .append("version: ").append(yaml(page.version())).append('\n')
                .append("status: ").append(page.status()).append('\n')
                .append("codeCommit: ").append(yaml(page.codeCommit())).append('\n')
                .append("generatedAt: ").append(yaml(page.generatedAt())).append('\n')
                .append("---\n\n# ").append(page.title()).append("\n\n")
                .append(page.summary()).append("\n\n");
        section(out, "业务规则", page.productRules());
        section(out, "处理流程", page.processSteps());
        section(out, "数据与配置影响", page.dataImpacts());
        section(out, "异常与边界条件", page.boundaryConditions());
        section(out, "代码入口", page.codeSymbols(), "尚未关联代码实现");
        section(out, "测试与验收", page.acceptanceCriteria());
        if (!page.testPoints().isEmpty()) section(out, "测试建议", page.testPoints());
        out.append("## 测试执行状态\n\n- ").append(page.testKnowledge().summary()).append("\n\n");
        section(out, "风险与存疑", page.risks());
        if (!page.relations().isEmpty()) {
            out.append("## 关联功能\n\n");
            page.relations().forEach(relation -> out.append("- **").append(relation.label()).append("** (`")
                    .append(relation.targetFeatureId()).append("`)：").append(text(relation.description())).append('\n'));
            out.append('\n');
        }
        if (!page.evidence().isEmpty()) {
            out.append("## 原始证据\n\n");
            for (Evidence evidence : page.evidence()) {
                out.append("### ").append(fallback(evidence.title(), evidence.type())).append("\n\n")
                        .append("- 类型：").append(text(evidence.type())).append('\n')
                        .append("- 来源：").append(text(evidence.source())).append('\n')
                        .append("- 版本：").append(text(evidence.version())).append('\n');
                line(out, "位置", evidence.location());
                line(out, "文件", evidence.filePath());
                line(out, "符号", evidence.symbol());
                line(out, "Commit", evidence.commit());
                line(out, "核验状态", evidence.verificationStatus());
                if (!text(evidence.excerpt()).isBlank()) out.append("\n> ").append(evidence.excerpt().replace("\n", "\n> ")).append("\n");
                out.append('\n');
            }
        }
        return out.toString();
    }


    /** 归一化测试知识，缺失时以“没有真实执行快照”兜底。 */
    private TestKnowledge testKnowledge(TestKnowledge value) {
        if (value == null) {
            return new TestKnowledge("NOT_AVAILABLE", "", "没有真实执行快照", List.of());
        }
        return new TestKnowledge(fallback(value.executionStatus(), "NOT_AVAILABLE"),
                text(value.executionReference()), fallback(value.summary(), "没有真实执行快照"), list(value.cases()));
    }

    /** 归一化版本变化，缺失时以“尚未记录结构化版本变化”兜底。 */
    private VersionChange versionChange(VersionChange value, String version) {
        if (value == null) return new VersionChange("UNKNOWN", "", version, "尚未记录结构化版本变化");
        return new VersionChange(fallback(value.changeType(), "UNKNOWN"), text(value.baseVersion()),
                fallback(value.version(), version), fallback(value.summary(), "尚未记录结构化版本变化"));
    }

    /** 计算知识质量：有定义时复用并补齐缺省值，否则按需求/代码证据缺失情况生成待评审质量。 */
    private KnowledgeQuality quality(KnowledgeQuality value, List<RequirementSource> requirements,
                                     List<CodeEntry> codeEntries) {
        if (value != null) {
            return new KnowledgeQuality(fallback(value.reviewStatus(), "PENDING_REVIEW"),
                    value.requirementEvidenceCount(), value.codeEvidenceCount(), value.realTestExecution(),
                    list(value.missing()));
        }
        List<String> missing = new ArrayList<>();
        if (requirements == null || requirements.isEmpty()) missing.add("需求证据");
        if (codeEntries == null || codeEntries.isEmpty()) missing.add("代码证据");
        missing.add("真实测试执行快照");
        return new KnowledgeQuality("PENDING_REVIEW", requirements == null ? 0 : requirements.size(),
                codeEntries == null ? 0 : codeEntries.size(), false, List.copyOf(missing));
    }

    private void section(StringBuilder out, String title, List<String> items) {
        section(out, title, items, "暂无已核验内容");
    }

    private void section(StringBuilder out, String title, List<String> items, String emptyMessage) {
        out.append("## ").append(title).append("\n\n");
        if (items.isEmpty()) out.append("- ").append(emptyMessage).append("\n\n");
        else {
            items.forEach(item -> out.append("- ").append(item).append('\n'));
            out.append('\n');
        }
    }

    private void line(StringBuilder out, String label, String value) {
        if (!text(value).isBlank()) out.append("- ").append(label).append("：").append(value.trim()).append('\n');
    }

    private String yaml(String value) {
        return "\"" + text(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 以格式化 JSON 写文件，并补一个结尾换行。 */
    private void writeJson(Path file, Object value) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8) + "\n", StandardCharsets.UTF_8);
    }

    /** 原子替换发布目录：先备份旧版本，失败时回滚备份。 */
    private void publish(Path staging, Path target) throws IOException {
        synchronized (publishLock) {
            Files.createDirectories(target.getParent());
            Path backup = target.resolveSibling("." + target.getFileName() + ".old-" + UUID.randomUUID());
            boolean backedUp = false;
            try {
                if (Files.exists(target)) {
                    move(target, backup);
                    backedUp = true;
                }
                move(staging, target);
                if (backedUp) deleteTree(backup);
            } catch (IOException exception) {
                if (!Files.exists(target) && backedUp && Files.exists(backup)) move(backup, target);
                throw exception;
            }
        }
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            log.debug("Atomic move is unsupported for the Wiki filesystem; using a regular move", exception);
            Files.move(source, target);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            deleteTree(path);
        }
        catch (IOException exception) {
            log.warn("Best-effort Wiki cleanup failed; path is omitted from logs", exception);
        }
    }

    /** 递归删除目录树（先删子项后删自身）。 */
    private void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        }
    }

    private String fallback(String value, String fallback) {
        return text(value).isBlank() ? fallback : value.trim();
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private <T> List<T> list(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
