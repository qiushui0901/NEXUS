package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.ClaimVectorGenerationManifest;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.GenerationStatus;
import com.example.requirementrag.retrieval.EmbeddingBatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Claim 向量构建服务测试：验证构建流水线、状态转换、回滚与失败保护。
 * 用真实 SQLiteKnowledgeClaimVectorStore（@TempDir）+ Mock Qdrant Store。
 * 覆盖 Review 2/3/6/8/9 的修复：scope 化 alias、版本绑定、alias 切换失败不返回成功、
 * 分页流式构建、回滚取最近退役代际。
 */
class KnowledgeClaimVectorBuildServiceTest {

    /** 与生产一致的 scope 化 live alias（properties.liveAlias("proj-1","v1")，含稳定 hash 后缀）。 */
    private String liveAlias() {
        return properties.liveAlias("proj-1", "v1");
    }

    @TempDir
    Path tempDir;

    private MultiSourceKnowledgeStore knowledgeStore;
    private SQLiteKnowledgeClaimVectorStore vectorStore;
    private KnowledgeClaimVectorQdrantStore qdrantStore;
    private KnowledgeClaimVectorTextComposer textComposer;
    private EmbeddingBatcher embeddingBatcher;
    private EmbeddingModel embeddingModel;
    private KnowledgeClaimVectorProperties properties;
    private KnowledgeClaimVectorBuildService buildService;

    @BeforeEach
    void setUp() {
        knowledgeStore = mock(MultiSourceKnowledgeStore.class);
        qdrantStore = mock(KnowledgeClaimVectorQdrantStore.class);
        embeddingBatcher = mock(EmbeddingBatcher.class);
        embeddingModel = mock(EmbeddingModel.class);
        properties = new KnowledgeClaimVectorProperties(
                true, true, true, true,
                "knowledge_claims_live", "knowledge-claim-vector-v1", "knowledge-claim-text-v1",
                200, 3, 32, 3, 2, tempDir.resolve("test-vector.db").toString());
        vectorStore = new SQLiteKnowledgeClaimVectorStore(properties);
        textComposer = new KnowledgeClaimVectorTextComposer(properties);
        when(embeddingModel.dimensions()).thenReturn(8);
        buildService = new KnowledgeClaimVectorBuildService(
                knowledgeStore, vectorStore, qdrantStore, textComposer,
                embeddingBatcher, embeddingModel, properties);
    }

    private KnowledgeClaimRecord claim(String id, SourceType type, String subject, String factKey) {
        return new KnowledgeClaimRecord(
                id, "proj-1", "doc-ver-1", type, Authority.PRIMARY,
                factKey, subject, "必须支持", "30秒", "", "", "ACTIVE",
                0.95, null, null, "RULE", "run-1", "2025-01-01T00:00:00Z",
                "2025-01-01T00:00:00Z");
    }

    /** 分页读取桩：第一页返回给定 Claims，后续 offset 默认空列表终止流式循环。 */
    private void stubClaims(List<KnowledgeClaimRecord> claims) {
        when(knowledgeStore.findPublishedClaimsByProjectVersionPage(eq("proj-1"), eq("v1"), anyInt(), eq(0L)))
                .thenReturn(claims);
    }

    /** 通用嵌入桩：返回与输入文本数一致的向量（适配分块调用）。 */
    private void stubEmbeddings() {
        when(embeddingBatcher.embedAll(anyList())).thenAnswer(invocation -> {
            List<?> texts = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(new float[]{0.1f * (i + 1)});
            }
            return vectors;
        });
    }

    // ── 构建流水线 ──────────────────────────────────────────────────────

    @Test
    void buildHappyPathProducesActiveGeneration() {
        List<KnowledgeClaimRecord> claims = List.of(
                claim("c-1", SourceType.REQUIREMENT, "系统需要支持登录", "authn#login"),
                claim("c-2", SourceType.PARAMETER_TABLE, "超时时间30秒", "param#timeout"));
        stubClaims(claims);
        stubEmbeddings();

        ClaimVectorGenerationManifest result = buildService.build("proj-1", "v1");

        assertThat(result.status()).isEqualTo(GenerationStatus.ACTIVE);
        assertThat(result.projectId()).isEqualTo("proj-1");
        assertThat(result.businessVersion()).isEqualTo("v1");
        assertThat(result.expectedPointCount()).isEqualTo(2);
        assertThat(result.indexedPointCount()).isEqualTo(2);
        // 高（Review 2）：物理 collection 与 alias 均按 scope 隔离
        assertThat(result.physicalCollection()).startsWith(liveAlias() + "-");

        // 验证 Qdrant 调用：建集合 + 分块追加 + 校验点数 + scope 化 alias 切换
        verify(qdrantStore).createCollectionIfAbsent(anyString(), eq(8));
        verify(qdrantStore).appendPoints(anyString(), anyList(), anyList());
        verify(qdrantStore).verifyPointCount(anyString(), eq(2));
        verify(qdrantStore).switchAlias(eq(liveAlias()), anyString());
    }

    @Test
    void buildFiltersExcludedSourceTypes() {
        List<KnowledgeClaimRecord> claims = List.of(
                claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a"),
                claim("c-2", SourceType.CODE, "代码B", "fk-b"),
                claim("c-3", SourceType.TEST_RESULT, "测试C", "fk-c"));
        stubClaims(claims);
        stubEmbeddings();

        ClaimVectorGenerationManifest result = buildService.build("proj-1", "v1");

        // CODE 和 TEST_RESULT 被排除，只有 REQUIREMENT 投影
        assertThat(result.expectedPointCount()).isEqualTo(1);
        assertThat(result.indexedPointCount()).isEqualTo(1);
    }

    @Test
    void buildReusableGenerationSkipsRebuild() {
        // 第一次构建
        stubClaims(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        stubEmbeddings();
        ClaimVectorGenerationManifest first = buildService.build("proj-1", "v1");

        // 第二次构建同一指纹——应跳过
        ClaimVectorGenerationManifest second = buildService.build("proj-1", "v1");

        assertThat(second.generationId()).isEqualTo(first.generationId());
        // 只嵌入一次（第一次）
        verify(embeddingBatcher, times(1)).embedAll(any());
    }

    @Test
    void buildStreamsAcrossMultiplePages() {
        // 高（Review 8）：单页装不下时按页流式读取——两页合成为同一代际
        List<KnowledgeClaimRecord> page1 = List.of(
                claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a"));
        List<KnowledgeClaimRecord> page2 = List.of(
                claim("c-2", SourceType.PARAMETER_TABLE, "参数B", "fk-b"));
        when(knowledgeStore.findPublishedClaimsByProjectVersionPage(eq("proj-1"), eq("v1"), anyInt(), eq(0L)))
                .thenReturn(page1);
        when(knowledgeStore.findPublishedClaimsByProjectVersionPage(eq("proj-1"), eq("v1"), anyInt(), eq(1L)))
                .thenReturn(page2);

        // 分块嵌入：两个 chunk 各 1 条
        when(embeddingBatcher.embedAll(anyList())).thenAnswer(invocation -> {
            List<?> texts = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(new float[]{0.1f});
            }
            return vectors;
        });

        ClaimVectorGenerationManifest result = buildService.build("proj-1", "v1");

        assertThat(result.expectedPointCount()).isEqualTo(2);
        assertThat(result.indexedPointCount()).isEqualTo(2);
        // 两 DB 页合成 2 条 composed → 每页一个嵌入分块（EMBED_CHUNK_SIZE=64），共 2 次嵌入；
        // 关键断言：确认流式读取真的越过了第二页（offset 1），而不是一次性全量加载；
        // 两遍流式会各读两页，故用 atLeastOnce() 验证 offset 0/1 均被读取。
        verify(embeddingBatcher, times(2)).embedAll(any());
        verify(knowledgeStore, atLeastOnce()).findPublishedClaimsByProjectVersionPage(eq("proj-1"), eq("v1"), anyInt(), eq(0L));
        verify(knowledgeStore, atLeastOnce()).findPublishedClaimsByProjectVersionPage(eq("proj-1"), eq("v1"), anyInt(), eq(1L));
        verify(qdrantStore).verifyPointCount(anyString(), eq(2));
    }

    // ── 失败保护 ──────────────────────────────────────────────────────

    @Test
    void embeddingFailureMarksGenerationFailed() {
        stubClaims(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        when(embeddingBatcher.embedAll(anyList()))
                .thenThrow(new RuntimeException("embedding service unavailable"));

        assertThatThrownBy(() -> buildService.build("proj-1", "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("嵌入失败");

        // 嵌入失败：未建集合、未写点、未切 alias
        verify(qdrantStore, never()).createCollectionIfAbsent(anyString(), anyInt());
        verify(qdrantStore, never()).appendPoints(anyString(), anyList(), anyList());
        verify(qdrantStore, never()).switchAlias(anyString(), anyString());

        // 代际标记为 FAILED
        Optional<ClaimVectorGenerationManifest> gen = vectorStore.findLatestGeneration("proj-1", "v1");
        assertThat(gen).isPresent();
        assertThat(gen.get().status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(gen.get().warningsJson()).contains("KNOWLEDGE_CLAIM_VECTOR_BUILD_FAILED");
    }

    @Test
    void qdrantWriteFailureMarksGenerationFailedAndAliasUnchanged() {
        stubClaims(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        stubEmbeddings();
        doThrow(new RuntimeException("Qdrant connection refused"))
                .when(qdrantStore).appendPoints(anyString(), anyList(), anyList());

        assertThatThrownBy(() -> buildService.build("proj-1", "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("写入失败");

        // alias 未切换
        verify(qdrantStore, never()).switchAlias(anyString(), anyString());

        Optional<ClaimVectorGenerationManifest> gen = vectorStore.findLatestGeneration("proj-1", "v1");
        assertThat(gen).isPresent();
        assertThat(gen.get().status()).isEqualTo(GenerationStatus.FAILED);
    }

    @Test
    void verifyCountFailureMarksGenerationFailed() {
        stubClaims(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        stubEmbeddings();
        doThrow(new RuntimeException("count mismatch"))
                .when(qdrantStore).verifyPointCount(anyString(), anyInt());

        assertThatThrownBy(() -> buildService.build("proj-1", "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("写入失败");

        verify(qdrantStore, never()).switchAlias(anyString(), anyString());
        Optional<ClaimVectorGenerationManifest> gen = vectorStore.findLatestGeneration("proj-1", "v1");
        assertThat(gen).isPresent();
        assertThat(gen.get().status()).isEqualTo(GenerationStatus.FAILED);
    }

    @Test
    void aliasSwitchFailureMarksFailedAndDoesNotPublishActive() {
        // 高（Review 6）：alias 切换失败必须 FAILED 且保持非 ACTIVE——不再返回虚假成功
        stubClaims(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        stubEmbeddings();
        doThrow(new RuntimeException("alias switch timeout"))
                .when(qdrantStore).switchAlias(anyString(), anyString());

        assertThatThrownBy(() -> buildService.build("proj-1", "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alias 切换失败");

        // SQLite 无 ACTIVE 代际；最新代际为 FAILED（ALIAS_SWITCH_FAILED）
        Optional<ClaimVectorGenerationManifest> active = vectorStore.findActiveGeneration("proj-1", "v1");
        assertThat(active).isEmpty();
        Optional<ClaimVectorGenerationManifest> gen = vectorStore.findLatestGeneration("proj-1", "v1");
        assertThat(gen).isPresent();
        assertThat(gen.get().status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(gen.get().warningsJson()).contains("KNOWLEDGE_CLAIM_VECTOR_ALIAS_SWITCH_FAILED");
    }

    @Test
    void markActiveFailureRollsAliasBackToPreviousTarget() {
        // 高（Review 4）：markActive（SQLite 权威提交）失败时 Qdrant alias 已切到新代际——
        // 必须补偿把 alias 切回前序目标，消除 SQLite/Qdrant 分叉窗口。
        SQLiteKnowledgeClaimVectorStore spyStore = spy(vectorStore);
        doThrow(new RuntimeException("SQLite busy"))
                .when(spyStore).markActive(anyString(), anyString());
        KnowledgeClaimVectorBuildService spyBuildService = new KnowledgeClaimVectorBuildService(
                knowledgeStore, spyStore, qdrantStore, textComposer,
                embeddingBatcher, embeddingModel, properties);
        stubClaims(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        stubEmbeddings();
        String previousTarget = "old-collection-1";
        when(qdrantStore.aliasTarget(eq(liveAlias()))).thenReturn(previousTarget);

        assertThatThrownBy(() -> spyBuildService.build("proj-1", "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("代际激活失败");

        // 补偿：alias 已切回前序目标；SQLite 无 ACTIVE；半成品物理 collection 被清理（高：Review 5）
        verify(qdrantStore).rollbackAlias(eq(liveAlias()), eq(previousTarget));
        verify(qdrantStore).deleteCollection(anyString());
        assertThat(spyStore.findActiveGeneration("proj-1", "v1")).isEmpty();
    }

    @Test
    void markActiveFailureOnFirstBuildDeletesAliasAndCollection() {
        // 高（Review 4）：首次构建无前序 alias 目标——markActive 失败时必须删除新 alias
        // （恢复无 alias 状态），并清理本代际半成品物理 collection（高：Review 5），
        // 避免残留错误指向未激活代际的 alias 与孤儿集合。
        SQLiteKnowledgeClaimVectorStore spyStore = spy(vectorStore);
        doThrow(new RuntimeException("SQLite busy"))
                .when(spyStore).markActive(anyString(), anyString());
        KnowledgeClaimVectorBuildService spyBuildService = new KnowledgeClaimVectorBuildService(
                knowledgeStore, spyStore, qdrantStore, textComposer,
                embeddingBatcher, embeddingModel, properties);
        stubClaims(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        stubEmbeddings();
        // 首次构建：alias 尚不存在（aliasTarget 返回 null，无前序目标）
        when(qdrantStore.aliasTarget(eq(liveAlias()))).thenReturn(null);

        assertThatThrownBy(() -> spyBuildService.build("proj-1", "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("代际激活失败");

        // 补偿：删除新 alias（而非回滚——无前序目标）；半成品集合已清理；SQLite 无 ACTIVE
        verify(qdrantStore).deleteAlias(eq(liveAlias()));
        verify(qdrantStore, never()).rollbackAlias(anyString(), anyString());
        verify(qdrantStore).deleteCollection(anyString());
        assertThat(spyStore.findActiveGeneration("proj-1", "v1")).isEmpty();
    }

    @Test
    void secondPassDriftFailsGeneration() {
        // 高（Review 8）：两遍流式读取间数据漂移（第一遍统计 2 条，第二遍只读回 1 条）
        // 必须拒绝发布，避免 manifest 计数与实际投影不一致。
        when(knowledgeStore.findPublishedClaimsByProjectVersionPage(eq("proj-1"), eq("v1"), anyInt(), eq(0L)))
                .thenReturn(List.of(
                        claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a"),
                        claim("c-2", SourceType.PARAMETER_TABLE, "参数B", "fk-b")))
                .thenReturn(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        stubEmbeddings();

        assertThatThrownBy(() -> buildService.build("proj-1", "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("漂移");

        verify(qdrantStore, never()).switchAlias(anyString(), anyString());
        Optional<ClaimVectorGenerationManifest> gen = vectorStore.findLatestGeneration("proj-1", "v1");
        assertThat(gen).isPresent();
        assertThat(gen.get().status()).isEqualTo(GenerationStatus.FAILED);
    }

    @Test
    void equalCountReplacementDriftFailsGeneration() {
        // 高（Review 3）：两遍读取间发生等量替换（第一遍 [A,B]，第二遍 [A已变更,B]，
        // 数量仍是 2）——仅比较数量检测不到，必须逐项校验 claimId+documentVersionId+textHash
        // 才能发现 A 被替换为不同内容。
        when(knowledgeStore.findPublishedClaimsByProjectVersionPage(eq("proj-1"), eq("v1"), anyInt(), eq(0L)))
                .thenReturn(List.of(
                        claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a"),
                        claim("c-2", SourceType.PARAMETER_TABLE, "参数B", "fk-b")))
                .thenReturn(List.of(
                        claim("c-1", SourceType.REQUIREMENT, "需求A已变更", "fk-a"),
                        claim("c-2", SourceType.PARAMETER_TABLE, "参数B", "fk-b")));
        stubEmbeddings();

        assertThatThrownBy(() -> buildService.build("proj-1", "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("漂移");

        verify(qdrantStore, never()).switchAlias(anyString(), anyString());
        Optional<ClaimVectorGenerationManifest> gen = vectorStore.findLatestGeneration("proj-1", "v1");
        assertThat(gen).isPresent();
        assertThat(gen.get().status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(gen.get().warningsJson()).contains("第二遍流式漂移");
    }

    // ── 边界 ──────────────────────────────────────────────────────────

    @Test
    void buildWithNoEligibleClaimsThrows() {
        stubClaims(List.of(claim("c-1", SourceType.CODE, "代码", "fk")));

        assertThatThrownBy(() -> buildService.build("proj-1", "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无可投影 Claim");

        // 未写入任何东西
        verify(qdrantStore, never()).appendPoints(anyString(), anyList(), anyList());
        verify(qdrantStore, never()).switchAlias(anyString(), anyString());
    }

    // ── 回滚 ──────────────────────────────────────────────────────────

    @Test
    void rollbackRestoresPreviousRetiredGeneration() {
        // 第一次构建
        stubClaims(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        stubEmbeddings();
        ClaimVectorGenerationManifest first = buildService.build("proj-1", "v1");

        // 第二次构建——不同 subject 产生不同 textHash→不同指纹→新代际
        stubClaims(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A已变更", "fk-a")));
        ClaimVectorGenerationManifest second = buildService.build("proj-1", "v1");

        assertThat(first.generationId()).isNotEqualTo(second.generationId());
        assertThat(second.status()).isEqualTo(GenerationStatus.ACTIVE);

        // 回滚到上一代（高：Review 9——取最近退役代际，而非最旧）
        Optional<ClaimVectorGenerationManifest> restored = buildService.rollback("proj-1", "v1");

        assertThat(restored).isPresent();
        assertThat(restored.get().generationId()).isEqualTo(first.generationId());
        assertThat(restored.get().status()).isEqualTo(GenerationStatus.ACTIVE);

        // Qdrant alias 按 scope 化切回旧 collection
        verify(qdrantStore).rollbackAlias(eq(liveAlias()), eq(first.physicalCollection()));
    }

    @Test
    void rollbackWithNoRetiredReturnsEmpty() {
        stubClaims(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        stubEmbeddings();
        buildService.build("proj-1", "v1");

        Optional<ClaimVectorGenerationManifest> restored = buildService.rollback("proj-1", "v1");

        assertThat(restored).isEmpty();
        verify(qdrantStore, never()).rollbackAlias(anyString(), anyString());
    }

    // ── 指定代际回滚（高：Review 10）────────────────────────────────────

    @Test
    void rollbackToRestoresSpecifiedGeneration() {
        stubClaims(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        stubEmbeddings();
        ClaimVectorGenerationManifest first = buildService.build("proj-1", "v1");

        stubClaims(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A已变更", "fk-a")));
        ClaimVectorGenerationManifest second = buildService.build("proj-1", "v1");

        Optional<ClaimVectorGenerationManifest> restored =
                buildService.rollbackTo("proj-1", "v1", first.generationId());

        assertThat(restored).isPresent();
        assertThat(restored.get().generationId()).isEqualTo(first.generationId());
        assertThat(restored.get().status()).isEqualTo(GenerationStatus.ACTIVE);
        // 当前 ACTIVE 恢复为第一代
        Optional<ClaimVectorGenerationManifest> active = buildService.findActive("proj-1", "v1");
        assertThat(active).isPresent();
        assertThat(active.get().generationId()).isEqualTo(first.generationId());
        verify(qdrantStore).rollbackAlias(eq(liveAlias()), eq(first.physicalCollection()));
    }

    @Test
    void rollbackToUnknownOrWrongScopeOrNotRetiredReturnsEmpty() {
        // 未知代际
        assertThat(buildService.rollbackTo("proj-1", "v1", "does-not-exist")).isEmpty();
        // 其他 scope 的代际
        stubClaims(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        stubEmbeddings();
        ClaimVectorGenerationManifest active = buildService.build("proj-1", "v1");
        // 其他项目尝试回滚到本 scope 代际——scope 不匹配拒绝
        assertThat(buildService.rollbackTo("proj-other", "v1", active.generationId())).isEmpty();
    }

    // ── findActive ──────────────────────────────────────────────────────

    @Test
    void findActiveReturnsActiveGeneration() {
        stubClaims(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        stubEmbeddings();
        buildService.build("proj-1", "v1");

        Optional<ClaimVectorGenerationManifest> active = buildService.findActive("proj-1", "v1");

        assertThat(active).isPresent();
        assertThat(active.get().status()).isEqualTo(GenerationStatus.ACTIVE);
    }

    @Test
    void findActiveReturnsEmptyWhenNoneExists() {
        Optional<ClaimVectorGenerationManifest> active = buildService.findActive("proj-1", "v1");
        assertThat(active).isEmpty();
    }
}
