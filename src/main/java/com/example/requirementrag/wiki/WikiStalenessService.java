package com.example.requirementrag.wiki;

import com.example.requirementrag.code.GitDiffService;
import com.example.requirementrag.code.GitDiffService.GitDiffResult;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.wiki.WikiModels.Page;
import com.example.requirementrag.wiki.WikiModels.PageSummary;
import com.example.requirementrag.wiki.WikiModels.PageType;
import com.example.requirementrag.wiki.WikiModels.RequirementSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 基于 Git commit 与需求内容哈希的 Wiki 失效检测：只读计算，不修改已发布内容。 */
@Service
public class WikiStalenessService {
    private static final Logger log = LoggerFactory.getLogger(WikiStalenessService.class);

    /** 单个页面过期项：页面标识、类型、标题与过期原因。 */
    public record StalePage(String featureId, PageType pageType, String title, List<String> reasons) {}

    /** 版本级过期报告：当前代码提交、受影响的页面清单与整体是否过期。 */
    public record StaleReport(
            String projectId,
            String version,
            String publishedCodeCommit,
            String currentCodeCommit,
            boolean stale,
            List<StalePage> pages
    ) {}

    private final WikiRepository repository;
    private final GitDiffService gitDiffService;
    private final QdrantHybridStore documentStore;
    private final ProjectRegistry projectRegistry;

    public WikiStalenessService(WikiRepository repository, GitDiffService gitDiffService,
                                QdrantHybridStore documentStore, ProjectRegistry projectRegistry) {
        this.repository = repository;
        this.gitDiffService = gitDiffService;
        this.documentStore = documentStore;
        this.projectRegistry = projectRegistry;
    }

    /** 检测指定版本 Wiki 的过期页面：代码 commit 差异 + 需求内容哈希差异。 */
    public StaleReport staleness(String projectId, String version) {
        var index = repository.getIndex(projectId, version);
        List<Page> pages = index.pages().stream()
                .map(summary -> repository.getPage(projectId, version, summary.featureId()))
                .toList();

        String currentCommit = currentCommit(projectId);
        Map<String, List<String>> codeReasons = codeReasons(projectId, index.codeCommit(), currentCommit, pages);
        Map<String, List<String>> requirementReasons = requirementReasons(projectId, version, pages);

        List<StalePage> stalePages = new ArrayList<>();
        for (PageSummary summary : index.pages()) {
            List<String> reasons = new ArrayList<>();
            reasons.addAll(codeReasons.getOrDefault(summary.featureId(), List.of()));
            reasons.addAll(requirementReasons.getOrDefault(summary.featureId(), List.of()));
            if (!reasons.isEmpty()) {
                stalePages.add(new StalePage(summary.featureId(), summary.pageType() == null ? PageType.FEATURE
                        : summary.pageType(), summary.title(), List.copyOf(reasons)));
            }
        }
        return new StaleReport(projectId, version, index.codeCommit(), currentCommit, !stalePages.isEmpty(),
                List.copyOf(stalePages));
    }

    /** 代码失效检测：HEAD 落后或 diff 命中页面代码入口文件时标记过期。 */
    private Map<String, List<String>> codeReasons(String projectId, String publishedCommit, String currentCommit,
                                                  List<Page> pages) {
        Map<String, List<String>> reasons = new HashMap<>();
        if (currentCommit == null) return reasons;
        if (publishedCommit == null || publishedCommit.isBlank()) {
            for (Page page : pages) reasons.computeIfAbsent(page.featureId(), key -> new ArrayList<>())
                    .add("页面未绑定代码提交，无法确认新鲜度");
            return reasons;
        }
        if (currentCommit.equals(publishedCommit)) return reasons;
        List<String> changedPaths;
        try {
            GitDiffResult diff = gitDiffService.diff(projectId, publishedCommit, currentCommit);
            changedPaths = diff.changedPaths();
        } catch (Exception exception) {
            log.warn("Git diff failed for project {}; marking the whole version stale", projectId, exception);
            for (Page page : pages) reasons.computeIfAbsent(page.featureId(), key -> new ArrayList<>())
                    .add("代码提交从 " + publishedCommit + " 变化到 " + currentCommit + "，但无法精确比对");
            return reasons;
        }
        if (changedPaths.isEmpty()) {
            for (Page page : pages) reasons.computeIfAbsent(page.featureId(), key -> new ArrayList<>())
                    .add("代码提交从 " + publishedCommit + " 变化到 " + currentCommit);
            return reasons;
        }
        List<String> affectedPaths = changedPaths;
        for (Page page : pages) {
            boolean touched = page.codeEntries().stream()
                    .anyMatch(entry -> affectedPaths.contains(normalize(entry.filePath())));
            if (touched) reasons.computeIfAbsent(page.featureId(), key -> new ArrayList<>())
                    .add("代码提交从 " + publishedCommit + " 变化到 " + currentCommit + "，命中页面代码入口");
        }
        return reasons;
    }

    /** 需求失效检测：需求来源的当前内容哈希与发布哈希不一致时标记过期；读取失败则跳过（不误报）。 */
    private Map<String, List<String>> requirementReasons(String projectId, String version, List<Page> pages) {
        Map<String, List<String>> reasons = new HashMap<>();
        List<Page> tracked = pages.stream()
                .filter(page -> page.requirementSources().stream().anyMatch(source -> hasText(source.contentHash())))
                .toList();
        if (tracked.isEmpty()) return reasons;
        String collection = projectRegistry.resolveRequirementCollection(projectId);
        List<ChunkRecord> currentChunks;
        try {
            String documentId = tracked.get(0).requirementSources().get(0).documentId();
            currentChunks = documentStore.scrollVersion(collection, documentId, version);
        } catch (RuntimeException exception) {
            log.warn("Requirement scroll failed for project {}; skipping requirement staleness", projectId, exception);
            return reasons;
        }
        Map<String, String> currentByLocation = new HashMap<>();
        for (ChunkRecord chunk : currentChunks) {
            currentByLocation.put("parentOrder=" + chunk.parentOrder(), hasText(chunk.contentHash())
                    ? chunk.contentHash() : sha256(chunk.parentText()));
        }
        for (Page page : tracked) {
            for (RequirementSource source : page.requirementSources()) {
                String current = currentByLocation.getOrDefault(source.location(), "");
                if (!source.contentHash().equals(current)) {
                    reasons.computeIfAbsent(page.featureId(), key -> new ArrayList<>())
                            .add("需求来源 " + source.filename() + " 内容哈希已变化");
                }
            }
        }
        return reasons;
    }

    /** 读取当前 HEAD commit；仓库不可用时返回 null 并记录警告。 */
    private String currentCommit(String projectId) {
        try {
            return gitDiffService.latestCommit(projectId);
        } catch (Exception exception) {
            log.warn("git rev-parse HEAD failed for project {}; code staleness check skipped", projectId, exception);
            return null;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }

    /** 与构建管线一致的 SHA-256 内容哈希。 */
    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
