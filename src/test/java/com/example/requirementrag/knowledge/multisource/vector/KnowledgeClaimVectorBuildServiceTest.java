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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Claim 向量构建服务测试：验证构建流水线、状态转换、回滚与失败保护。
 * 用真实 SQLiteKnowledgeClaimVectorStore（@TempDir）+ Mock Qdrant Store。
 */
class KnowledgeClaimVectorBuildServiceTest {

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

    // ── 构建流水线 ──────────────────────────────────────────────────────

    @Test
    void buildHappyPathProducesActiveGeneration() {
        List<KnowledgeClaimRecord> claims = List.of(
                claim("c-1", SourceType.REQUIREMENT, "系统需要支持登录", "authn#login"),
                claim("c-2", SourceType.PARAMETER_TABLE, "超时时间30秒", "param#timeout"));
        when(knowledgeStore.findClaimsByProjectVersion("proj-1", "v1"))
                .thenReturn(claims);
        when(embeddingBatcher.embedAll(any())).thenReturn(List.of(
                new float[]{0.1f, 0.2f}, new float[]{0.3f, 0.4f}));

        ClaimVectorGenerationManifest result = buildService.build("proj-1", "v1");

        assertThat(result.status()).isEqualTo(GenerationStatus.ACTIVE);
        assertThat(result.projectId()).isEqualTo("proj-1");
        assertThat(result.businessVersion()).isEqualTo("v1");
        assertThat(result.expectedPointCount()).isEqualTo(2);
        assertThat(result.indexedPointCount()).isEqualTo(2);
        assertThat(result.physicalCollection()).startsWith("knowledge_claims_live-");

        // 验证 Qdrant 调用：写入物理 collection + alias 切换
        verify(qdrantStore).publishPhysicalCollection(anyString(), anyList(), anyList(), eq(8));
        verify(qdrantStore).switchAlias(eq("knowledge_claims_live"), anyString());
    }

    @Test
    void buildFiltersExcludedSourceTypes() {
        List<KnowledgeClaimRecord> claims = List.of(
                claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a"),
                claim("c-2", SourceType.CODE, "代码B", "fk-b"),
                claim("c-3", SourceType.TEST_RESULT, "测试C", "fk-c"));
        when(knowledgeStore.findClaimsByProjectVersion("proj-1", "v1"))
                .thenReturn(claims);
        when(embeddingBatcher.embedAll(any())).thenReturn(List.of(new float[]{0.1f}));

        ClaimVectorGenerationManifest result = buildService.build("proj-1", "v1");

        // CODE 和 TEST_RESULT 被排除，只有 REQUIREMENT 投影
        assertThat(result.expectedPointCount()).isEqualTo(1);
        assertThat(result.indexedPointCount()).isEqualTo(1);
    }

    @Test
    void buildReusableGenerationSkipsRebuild() {
        // 第一次构建
        when(knowledgeStore.findClaimsByProjectVersion("proj-1", "v1"))
                .thenReturn(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        when(embeddingBatcher.embedAll(any()))
                .thenReturn(List.of(new float[]{0.1f}));
        ClaimVectorGenerationManifest first = buildService.build("proj-1", "v1");

        // 第二次构建同一指纹——应跳过
        ClaimVectorGenerationManifest second = buildService.build("proj-1", "v1");

        assertThat(second.generationId()).isEqualTo(first.generationId());
        // 只嵌入一次（第一次）
        verify(embeddingBatcher, org.mockito.Mockito.times(1)).embedAll(any());
    }

    // ── 失败保护 ──────────────────────────────────────────────────────

    @Test
    void embeddingFailureMarksGenerationFailed() {
        when(knowledgeStore.findClaimsByProjectVersion("proj-1", "v1"))
                .thenReturn(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        when(embeddingBatcher.embedAll(any()))
                .thenThrow(new RuntimeException("embedding service unavailable"));

        assertThatThrownBy(() -> buildService.build("proj-1", "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("嵌入失败");

        // Qdrant 未被调用
        verify(qdrantStore, never()).publishPhysicalCollection(anyString(), anyList(), anyList(), anyInt());
        verify(qdrantStore, never()).switchAlias(anyString(), anyString());

        // 代际标记为 FAILED
        Optional<ClaimVectorGenerationManifest> gen = vectorStore.findLatestGeneration("proj-1", "v1");
        assertThat(gen).isPresent();
        assertThat(gen.get().status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(gen.get().warningsJson()).contains("KNOWLEDGE_CLAIM_VECTOR_BUILD_FAILED");
    }

    @Test
    void qdrantWriteFailureMarksGenerationFailedAndAliasUnchanged() {
        when(knowledgeStore.findClaimsByProjectVersion("proj-1", "v1"))
                .thenReturn(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        when(embeddingBatcher.embedAll(any()))
                .thenReturn(List.of(new float[]{0.1f}));
        doThrow(new RuntimeException("Qdrant connection refused"))
                .when(qdrantStore).publishPhysicalCollection(anyString(), anyList(), anyList(), anyInt());

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
    void aliasSwitchFailureDoesNotRollbackSqlite() {
        when(knowledgeStore.findClaimsByProjectVersion("proj-1", "v1"))
                .thenReturn(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        when(embeddingBatcher.embedAll(any()))
                .thenReturn(List.of(new float[]{0.1f}));
        doThrow(new RuntimeException("alias switch timeout"))
                .when(qdrantStore).switchAlias(anyString(), anyString());

        // 不抛异常——SQLite 已 ACTIVE，Qdrant reconciliation 修复
        ClaimVectorGenerationManifest result = buildService.build("proj-1", "v1");

        // SQLite 标记为 ACTIVE（权威）
        assertThat(result.status()).isEqualTo(GenerationStatus.ACTIVE);
    }

    // ── 边界 ──────────────────────────────────────────────────────────

    @Test
    void buildWithNoEligibleClaimsThrows() {
        when(knowledgeStore.findClaimsByProjectVersion("proj-1", "v1"))
                .thenReturn(List.of(claim("c-1", SourceType.CODE, "代码", "fk")));

        assertThatThrownBy(() -> buildService.build("proj-1", "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无可投影 Claim");

        // 未写入任何东西
        verify(qdrantStore, never()).publishPhysicalCollection(anyString(), anyList(), anyList(), anyInt());
    }

    // ── 回滚 ──────────────────────────────────────────────────────────

    @Test
    void rollbackRestoresPreviousRetiredGeneration() {
        // 第一次构建
        when(knowledgeStore.findClaimsByProjectVersion("proj-1", "v1"))
                .thenReturn(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        when(embeddingBatcher.embedAll(any()))
                .thenReturn(List.of(new float[]{0.1f}));
        ClaimVectorGenerationManifest first = buildService.build("proj-1", "v1");

        // 第二次构建——不同 subject 产生不同 textHash→不同指纹→新代际
        when(knowledgeStore.findClaimsByProjectVersion("proj-1", "v1"))
                .thenReturn(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A已变更", "fk-a")));
        when(embeddingBatcher.embedAll(any()))
                .thenReturn(List.of(new float[]{0.2f}));
        ClaimVectorGenerationManifest second = buildService.build("proj-1", "v1");

        assertThat(first.generationId()).isNotEqualTo(second.generationId());
        assertThat(second.status()).isEqualTo(GenerationStatus.ACTIVE);

        // 回滚到上一代
        Optional<ClaimVectorGenerationManifest> restored = buildService.rollback("proj-1", "v1");

        assertThat(restored).isPresent();
        assertThat(restored.get().generationId()).isEqualTo(first.generationId());
        assertThat(restored.get().status()).isEqualTo(GenerationStatus.ACTIVE);

        // Qdrant alias 切回旧 collection
        verify(qdrantStore).rollbackAlias(eq("knowledge_claims_live"),
                eq(first.physicalCollection()));
    }

    @Test
    void rollbackWithNoRetiredReturnsEmpty() {
        when(knowledgeStore.findClaimsByProjectVersion("proj-1", "v1"))
                .thenReturn(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        when(embeddingBatcher.embedAll(any())).thenReturn(List.of(new float[]{0.1f}));
        buildService.build("proj-1", "v1");

        Optional<ClaimVectorGenerationManifest> restored = buildService.rollback("proj-1", "v1");

        assertThat(restored).isEmpty();
        verify(qdrantStore, never()).rollbackAlias(anyString(), anyString());
    }

    // ── findActive ──────────────────────────────────────────────────────

    @Test
    void findActiveReturnsActiveGeneration() {
        when(knowledgeStore.findClaimsByProjectVersion("proj-1", "v1"))
                .thenReturn(List.of(claim("c-1", SourceType.REQUIREMENT, "需求A", "fk-a")));
        when(embeddingBatcher.embedAll(any())).thenReturn(List.of(new float[]{0.1f}));
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
