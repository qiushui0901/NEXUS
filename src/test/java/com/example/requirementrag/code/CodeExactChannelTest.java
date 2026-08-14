package com.example.requirementrag.code;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.CodeChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeExactChannelTest {

    @TempDir
    Path temp;

    private Path repository;
    private SQLiteSymbolGraphStore graphStore;
    private CodeQdrantStore qdrantStore;
    private RagProperties.Retrieval retrieval;
    private CodeKnowledgeService service;
    private String commitSha;

    @BeforeEach
    void setUp() throws Exception {
        repository = Files.createDirectory(temp.resolve("repo"));
        git(repository, "init");
        git(repository, "config", "user.email", "test@example.com");
        git(repository, "config", "user.name", "Test");
        Files.writeString(repository.resolve("HeroService.java"), """
                package demo;

                public class HeroService {
                    public void save() {
                        System.out.println("saved");
                    }
                }
                """);
        git(repository, "add", ".");
        git(repository, "commit", "-m", "initial");
        commitSha = git(repository, "rev-parse", "HEAD");
        graphStore = new SQLiteSymbolGraphStore(temp.resolve("graph").toString());
        CodeSymbol clazz = new CodeSymbol("cls-1", "test", commitSha, "java", "class",
                "demo.HeroService", "HeroService", "HeroService.java", 3, 8, false, false);
        CodeSymbol save = new CodeSymbol("m-1", "test", commitSha, "java", "method",
                "demo.HeroService.save", "save", "HeroService.java", 4, 6, false, false);
        graphStore.replaceSnapshot(new CodeScanner.ScanResult("test", commitSha, 1, List.of(),
                List.of(clazz, save), List.of(), List.of()));

        qdrantStore = mock(CodeQdrantStore.class);
        retrieval = mock(RagProperties.Retrieval.class);
        when(retrieval.resolvedCodeExactSymbolEnabled()).thenReturn(true);
        when(retrieval.resolvedCodeClassScopedEnabled()).thenReturn(true);
        when(retrieval.resolvedCodeStructuralRerankEnabled()).thenReturn(true);
        RagProperties properties = mock(RagProperties.class);
        when(properties.code()).thenReturn(new RagProperties.Code(
                "test", repository.toString(), "test-code", List.of(), List.of(), 1_000_000));
        when(properties.retrieval()).thenReturn(retrieval);
        ProjectRegistry projectRegistry = mock(ProjectRegistry.class);
        when(projectRegistry.require("test")).thenReturn(new RagProperties.ProjectConfig(
                "test", "Test", "test", "server", "req", "test-code", repository.toString(),
                null, null, List.of(), List.of(), 1_000_000));

        service = new CodeKnowledgeService(properties, projectRegistry,
                mock(CodeScanner.class), qdrantStore, graphStore, mock(CodeSemanticAnnotator.class),
                new CodeIndexLockService(), null, new CodeQueryAnalyzer());
    }

    private CodeChunk chunk(String id, String filePath, String symbolName, int startLine, String text) {
        return new CodeChunk(id, "test", "abc", filePath, "method", symbolName, startLine,
                startLine + 1, text, "hash-" + id);
    }

    @Test
    void pinsUniqueExactHitBeforeHybridResultsAndDeduplicates() {
        CodeChunk unrelated = chunk("h-1", "OtherService.java", "run", 10, "void run() {}");
        CodeChunk duplicate = chunk("h-2", "HeroService.java", "save", 4, "public void save() { … }");
        when(qdrantStore.hybridSearch(anyString(), anyString(), eq("test"), eq(10)))
                .thenReturn(List.of(unrelated, duplicate));

        List<CodeChunk> results = service.search("查找 HeroService.save 的实现位置。", "test", 10);

        assertThat(results).isNotEmpty();
        CodeChunk first = results.get(0);
        assertThat(first.symbolName()).isEqualTo("save");
        assertThat(first.filePath()).isEqualTo("HeroService.java");
        assertThat(first.text()).contains("public void save()");
        assertThat(results.stream().filter(chunk -> "save".equals(chunk.symbolName())).count()).isEqualTo(1);
        assertThat(results.get(1).id()).isEqualTo("h-1");
    }

    @Test
    void prependsAllOverloadHitsInStableOrder() throws Exception {
        Files.writeString(repository.resolve("ItemService.java"), """
                package demo;

                public class ItemService {
                    public void canAdd() {}
                    public void canAdd(int count) {}
                }
                """);
        git(repository, "add", ".");
        git(repository, "commit", "-m", "add ItemService");
        String itemSha = git(repository, "rev-parse", "HEAD");
        CodeSymbol clazz = new CodeSymbol("cls-2", "test", itemSha, "java", "class",
                "demo.ItemService", "ItemService", "ItemService.java", 3, 7, false, false);
        CodeSymbol first = new CodeSymbol("m-2", "test", itemSha, "java", "method",
                "demo.ItemService.canAdd", "canAdd", "ItemService.java", 4, 4, false, false);
        CodeSymbol second = new CodeSymbol("m-3", "test", itemSha, "java", "method",
                "demo.ItemService.canAdd", "canAdd", "ItemService.java", 5, 5, false, false);
        graphStore.replaceSnapshot(new CodeScanner.ScanResult("test", itemSha, 2, List.of(),
                List.of(clazz, first, second), List.of(), List.of()));
        when(qdrantStore.hybridSearch(anyString(), anyString(), eq("test"), eq(10))).thenReturn(List.of());

        List<CodeChunk> results = service.search("查找 ItemService.canAdd 的实现位置。", "test", 10);

        assertThat(results).extracting(CodeChunk::symbolName).startsWith("canAdd", "canAdd");
        assertThat(results).extracting(CodeChunk::startLine).startsWith(4, 5);
    }

    @Test
    void fallsBackToHybridWhenGraphHasNoExactHit() {
        CodeChunk hybrid = chunk("h-1", "VipService.java", "vipUsePrivilege", 8, "void vipUsePrivilege() {}");
        when(qdrantStore.hybridSearch(anyString(), anyString(), eq("test"), eq(10)))
                .thenReturn(List.of(hybrid));

        List<CodeChunk> results = service.search("查找 VipService.vipUsePrivilege 的实现位置。", "test", 10);

        assertThat(results).extracting(CodeChunk::id).containsExactly("h-1");
    }

    @Test
    void skipsExactChannelWhenFeatureFlagDisabled() {
        when(retrieval.resolvedCodeExactSymbolEnabled()).thenReturn(false);
        when(retrieval.resolvedCodeClassScopedEnabled()).thenReturn(false);
        CodeChunk hybrid = chunk("h-1", "HeroService.java", "save", 4, "public void save() {}");
        when(qdrantStore.hybridSearch(anyString(), anyString(), eq("test"), eq(10)))
                .thenReturn(List.of(hybrid));

        List<CodeChunk> results = service.search("查找 HeroService.save 的实现位置。", "test", 10);

        assertThat(results).extracting(CodeChunk::id).containsExactly("h-1");
    }

    @Test
    void usesClassScopedUnionRankingForClassNameOnlyQueries() {
        CodeChunk classHit = chunk("c-1", "HeroService.java", "save", 4, "public void save() {}");
        CodeChunk hybrid = chunk("h-1", "OtherService.java", "run", 10, "void run() {}");
        when(qdrantStore.searchWithClassScope(anyString(), anyString(), eq("test"),
                eq(List.of("HeroService.java")), nullable(String.class), eq(10)))
                .thenReturn(new CodeQdrantStore.ScopedSearchResult(
                        List.of(hybrid), List.of(classHit, hybrid), List.of(classHit, hybrid)));

        List<CodeChunk> results = service.search("在 HeroService 中由哪个方法实现？", "test", 10);

        // 服务直接采用类限定通道的并集排序结果（全局候选 + 类内补齐统一重排）
        assertThat(results).extracting(CodeChunk::id).containsExactly("c-1", "h-1");
    }

    @Test
    void explicitFilePathScopesClassSearchToThatFile() {
        CodeChunk classHit = chunk("c-1", "src/HeroService.java", "save", 4, "public void save() {}");
        // 显式路径查询：类限定范围必须直接用查询中的路径（而非类名查到的 HeroService.java）；
        // stub 只匹配 [src/HeroService.java]，若走类名查找会得到 ["HeroService.java"] 而无法命中该 stub
        when(qdrantStore.searchWithClassScope(anyString(), anyString(), eq("test"),
                eq(List.of("src/HeroService.java")), nullable(String.class), eq(10)))
                .thenReturn(new CodeQdrantStore.ScopedSearchResult(
                        List.of(classHit), List.of(classHit), List.of(classHit)));

        List<CodeChunk> results = service.search("解释 src/HeroService.java 中 HeroService 的实现", "test", 10);

        assertThat(results).extracting(CodeChunk::id).containsExactly("c-1");
    }

    @Test
    void fallsBackToHybridWhenClassScopeResolutionFails() {
        when(qdrantStore.searchWithClassScope(anyString(), anyString(), eq("test"),
                eq(List.of("HeroService.java")), nullable(String.class), eq(10))).thenReturn(null);
        CodeChunk hybrid = chunk("h-1", "HeroService.java", "save", 4, "public void save() {}");
        when(qdrantStore.hybridSearch(anyString(), anyString(), eq("test"), eq(10)))
                .thenReturn(List.of(hybrid));

        List<CodeChunk> results = service.search("在 HeroService 中由哪个方法实现？", "test", 10);

        assertThat(results).extracting(CodeChunk::id).containsExactly("h-1");
    }

    @Test
    void fallsBackToHybridWhenGraphStoreThrows() {
        SQLiteSymbolGraphStore broken = mock(SQLiteSymbolGraphStore.class);
        when(broken.latestCommit(anyString())).thenThrow(new IllegalStateException("db down"));
        RagProperties properties = mock(RagProperties.class);
        when(properties.code()).thenReturn(new RagProperties.Code(
                "test", repository.toString(), "test-code", List.of(), List.of(), 1_000_000));
        when(properties.retrieval()).thenReturn(retrieval);
        CodeKnowledgeService brokenService = new CodeKnowledgeService(properties,
                mock(ProjectRegistry.class), mock(CodeScanner.class), qdrantStore, broken,
                mock(CodeSemanticAnnotator.class), new CodeIndexLockService(), null, new CodeQueryAnalyzer());
        CodeChunk hybrid = chunk("h-1", "HeroService.java", "save", 4, "public void save() {}");
        when(qdrantStore.hybridSearch(anyString(), anyString(), eq("test"), eq(10)))
                .thenReturn(List.of(hybrid));

        List<CodeChunk> results = brokenService.search("查找 HeroService.save 的实现位置。", "test", 10);

        assertThat(results).extracting(CodeChunk::id).containsExactly("h-1");
    }

    @Test
    void exactChannelReadsSnapshotCommitContentInsteadOfDirtyWorktree() throws Exception {
        Path gitRepo = Files.createDirectory(temp.resolve("gitrepo"));
        git(gitRepo, "init");
        git(gitRepo, "config", "user.email", "test@example.com");
        git(gitRepo, "config", "user.name", "Test");
        Files.writeString(gitRepo.resolve("HeroService.java"), """
                public class HeroService {
                    public void save() {
                        System.out.println("v1");
                    }
                }
                """);
        git(gitRepo, "add", ".");
        git(gitRepo, "commit", "-m", "v1");
        String sha = git(gitRepo, "rev-parse", "HEAD");
        // 索引后工作区被修改（新增一行 + 内容变化）：精确通道必须仍按快照 commit 内容截取
        Files.writeString(gitRepo.resolve("HeroService.java"), """
                public class HeroService {
                    // injected line after indexing
                    public void save() {
                        System.out.println("v2");
                    }
                }
                """);

        SQLiteSymbolGraphStore graph = new SQLiteSymbolGraphStore(temp.resolve("graph-git").toString());
        CodeSymbol clazz = new CodeSymbol("cls-1", "test", sha, "java", "class",
                "demo.HeroService", "HeroService", "HeroService.java", 1, 5, false, false);
        CodeSymbol save = new CodeSymbol("m-1", "test", sha, "java", "method",
                "demo.HeroService.save", "save", "HeroService.java", 2, 4, false, false);
        graph.replaceSnapshot(new CodeScanner.ScanResult("test", sha, 1, List.of(),
                List.of(clazz, save), List.of(), List.of()));

        RagProperties properties = mock(RagProperties.class);
        when(properties.code()).thenReturn(new RagProperties.Code(
                "test", gitRepo.toString(), "test-code", List.of(), List.of(), 1_000_000));
        when(properties.retrieval()).thenReturn(retrieval);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        when(registry.require("test")).thenReturn(new RagProperties.ProjectConfig(
                "test", "Test", "test", "server", "req", "test-code", gitRepo.toString(),
                null, null, List.of(), List.of(), 1_000_000));
        CodeKnowledgeService gitService = new CodeKnowledgeService(properties, registry,
                mock(CodeScanner.class), qdrantStore, graph, mock(CodeSemanticAnnotator.class),
                new CodeIndexLockService(), null, new CodeQueryAnalyzer());
        when(qdrantStore.hybridSearch(anyString(), anyString(), eq("test"), eq(10))).thenReturn(List.of());

        List<CodeChunk> results = gitService.search("查找 HeroService.save 的实现位置。", "test", 10);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).text()).contains("v1").doesNotContain("v2").doesNotContain("injected line");
    }

    @Test
    void searchTraceRankedReflectsProductionSearchPathInSingleRetrieval() {
        CodeChunk hybridCandidate = chunk("h-1", "HeroService.java", "save", 4, "public void save() {}");
        when(qdrantStore.hybridSearchTrace(anyString(), anyString(), eq("test"), eq(10)))
                .thenReturn(new CodeQdrantStore.CodeSearchTrace(List.of(hybridCandidate),
                        List.of(hybridCandidate), List.of(), List.of()));

        CodeQdrantStore.CodeSearchTrace trace =
                service.searchTrace("查找 HeroService.save 的实现位置。", "test", 10);

        // ranked 必须与生产 search() 一致（精确通道置顶在首位）；
        // candidates 包含精确命中（并入候选池头部），与混合检索中的同符号候选去重
        assertThat(trace.ranked()).isNotEmpty();
        assertThat(trace.ranked().get(0).id()).isNotEqualTo("h-1");
        assertThat(trace.ranked().get(0).symbolName()).isEqualTo("save");
        assertThat(trace.candidates()).hasSize(1);
        assertThat(trace.candidates().get(0).symbolName()).isEqualTo("save");
        assertThat(trace.candidates().get(0).id()).isNotEqualTo("h-1");
        // 单次检索：candidates 与 ranked 来自同一次 hybridSearchTrace，不得再独立调用 hybridSearch
        verify(qdrantStore, times(1)).hybridSearchTrace(anyString(), anyString(), eq("test"), eq(10));
        verify(qdrantStore, never()).hybridSearch(anyString(), anyString(), eq("test"), eq(10));
    }

    private static String git(Path root, String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).directory(root.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        process.waitFor();
        return output;
    }
}
