package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.ClaimVectorGenerationInput;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.ClaimVectorGenerationManifest;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.GenerationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SQLiteKnowledgeClaimVectorStoreTest {

    @TempDir
    Path tempDir;

    private final KnowledgeClaimVectorProperties properties = new KnowledgeClaimVectorProperties(
            false, false, false, false,
            "knowledge_claims_live", "knowledge-claim-vector-v1", "knowledge-claim-text-v1",
            200, 3, 32, 3, 2, null);

    private SQLiteKnowledgeClaimVectorStore store() {
        // 用 null databasePath → compact 构造器回退到默认值，但测试需要临时路径
        KnowledgeClaimVectorProperties props = new KnowledgeClaimVectorProperties(
                false, false, false, false,
                "knowledge_claims_live", "knowledge-claim-vector-v1", "knowledge-claim-text-v1",
                200, 3, 32, 3, 2, tempDir.resolve("vector.db").toString());
        return new SQLiteKnowledgeClaimVectorStore(props);
    }

    // ===== 幂等迁移 =====

    @Test
    void idempotentInitialization() {
        // 连续创建两次不抛异常
        SQLiteKnowledgeClaimVectorStore store1 = store();
        SQLiteKnowledgeClaimVectorStore store2 = store();
        // 两次初始化后能正常写入
        ClaimVectorGenerationManifest manifest = manifest("gen-init", "BUILDING");
        store1.recordBuildStart(manifest, List.of());
        Optional<ClaimVectorGenerationManifest> found = store2.findGeneration("gen-init");
        assertThat(found).isPresent();
    }

    // ===== 代际 CRUD =====

    @Test
    void recordBuildStartPersistsManifestAndInputs() {
        SQLiteKnowledgeClaimVectorStore store = store();
        List<ClaimVectorGenerationInput> inputs = List.of(
                new ClaimVectorGenerationInput("gen-1", "claim-a", "dv-1", "hash-a", "2026-01-01"),
                new ClaimVectorGenerationInput("gen-1", "claim-b", "dv-1", "hash-b", "2026-01-01"));
        ClaimVectorGenerationManifest manifest = new ClaimVectorGenerationManifest(
                "gen-1", "immortal", "5.1", "default-fp-gen-1",
                "knowledge-claim-vector-v1", "knowledge-claim-text-v1",
                "test-model", 1024, null, GenerationStatus.BUILDING,
                2, 0, "[]", Instant.now().toString(), null, null);
        store.recordBuildStart(manifest, inputs);

        Optional<ClaimVectorGenerationManifest> found = store.findGeneration("gen-1");
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(GenerationStatus.BUILDING);
        assertThat(found.get().expectedPointCount()).isEqualTo(2);

        List<ClaimVectorGenerationInput> stored = store.findGenerationInputs("gen-1");
        assertThat(stored).hasSize(2);
        assertThat(stored).extracting(ClaimVectorGenerationInput::claimId)
                .containsExactlyInAnyOrder("claim-a", "claim-b");
    }

    @Test
    void updateStatusTransitionsToVerifyingThenSuccess() {
        SQLiteKnowledgeClaimVectorStore store = store();
        store.recordBuildStart(manifest("gen-2", "BUILDING"),
                List.of(new ClaimVectorGenerationInput("gen-2", "claim-1", "dv-1", "hash-1", "2026-01-01")));

        store.updateStatus("gen-2", GenerationStatus.VERIFYING, 0, "[]");
        assertThat(store.findGeneration("gen-2").orElseThrow().status()).isEqualTo(GenerationStatus.VERIFYING);

        store.updateStatus("gen-2", GenerationStatus.SUCCESS, 1, "[]");
        ClaimVectorGenerationManifest manifest = store.findGeneration("gen-2").orElseThrow();
        assertThat(manifest.status()).isEqualTo(GenerationStatus.SUCCESS);
        assertThat(manifest.indexedPointCount()).isEqualTo(1);
        assertThat(manifest.finishedAt()).isNotNull();
    }

    @Test
    void updateStatusToFailedRecordsWarning() {
        SQLiteKnowledgeClaimVectorStore store = store();
        store.recordBuildStart(manifest("gen-fail", "BUILDING"), List.of());

        store.updateStatus("gen-fail", GenerationStatus.FAILED, 0,
                "[\"KNOWLEDGE_CLAIM_VECTOR_BUILD_FAILED\"]");

        ClaimVectorGenerationManifest manifest = store.findGeneration("gen-fail").orElseThrow();
        assertThat(manifest.status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(manifest.warningsJson()).contains("BUILD_FAILED");
    }

    // ===== Active/Retired =====

    @Test
    void markActiveSetsStatusAndPhysicalCollection() {
        SQLiteKnowledgeClaimVectorStore store = store();
        store.recordBuildStart(manifest("gen-active", "SUCCESS"), List.of());

        Optional<ClaimVectorGenerationManifest> previous = store.markActive("gen-active", "knowledge_claims_live-gen-active");

        assertThat(previous).isEmpty();
        Optional<ClaimVectorGenerationManifest> active = store.findActiveGeneration("immortal", "5.1");
        assertThat(active).isPresent();
        assertThat(active.get().status()).isEqualTo(GenerationStatus.ACTIVE);
        assertThat(active.get().physicalCollection()).isEqualTo("knowledge_claims_live-gen-active");
        assertThat(active.get().publishedAt()).isNotNull();
    }

    @Test
    void markActiveRetiresPreviousActive() {
        SQLiteKnowledgeClaimVectorStore store = store();
        // 第一代际
        store.recordBuildStart(manifest("gen-old", "SUCCESS"), List.of());
        store.markActive("gen-old", "col-old");
        // 第二代际
        store.recordBuildStart(manifest("gen-new", "SUCCESS"),
                List.of(new ClaimVectorGenerationInput("gen-new", "claim-1", "dv-1", "hash-1", "2026-01-01")));
        Optional<ClaimVectorGenerationManifest> retired = store.markActive("gen-new", "col-new");

        assertThat(retired).isPresent();
        assertThat(retired.get().generationId()).isEqualTo("gen-old");
        assertThat(retired.get().status()).isEqualTo(GenerationStatus.RETIRED);
        // 同一 scope 只有一个 ACTIVE
        Optional<ClaimVectorGenerationManifest> active = store.findActiveGeneration("immortal", "5.1");
        assertThat(active).isPresent();
        assertThat(active.get().generationId()).isEqualTo("gen-new");
    }

    @Test
    void rollbackRestoresPreviousGeneration() {
        SQLiteKnowledgeClaimVectorStore store = store();
        store.recordBuildStart(manifest("gen-v1", "SUCCESS"), List.of());
        store.markActive("gen-v1", "col-v1");
        store.recordBuildStart(manifest("gen-v2", "SUCCESS"),
                List.of(new ClaimVectorGenerationInput("gen-v2", "claim-1", "dv-1", "hash-1", "2026-01-01")));
        store.markActive("gen-v2", "col-v2");

        // 回滚到 gen-v1
        Optional<ClaimVectorGenerationManifest> restored = store.rollbackTo("gen-v1");
        assertThat(restored).isPresent();
        assertThat(restored.get().generationId()).isEqualTo("gen-v1");

        Optional<ClaimVectorGenerationManifest> active = store.findActiveGeneration("immortal", "5.1");
        assertThat(active).isPresent();
        assertThat(active.get().generationId()).isEqualTo("gen-v1");
    }

    @Test
    void listRetiredForRollbackOrderByPublishedAtDesc() {
        SQLiteKnowledgeClaimVectorStore store = store();
        store.recordBuildStart(manifest("gen-1", "SUCCESS"), List.of());
        store.markActive("gen-1", "col-1");
        store.recordBuildStart(manifest("gen-2", "SUCCESS"), List.of());
        store.markActive("gen-2", "col-2");
        store.recordBuildStart(manifest("gen-3", "SUCCESS"), List.of());
        store.markActive("gen-3", "col-3");

        List<ClaimVectorGenerationManifest> retired = store.listRetiredForRollback("immortal", "5.1");
        assertThat(retired).hasSize(2);
        // 按 published_at 降序：gen-2 在 gen-1 之前
        assertThat(retired.get(0).generationId()).isEqualTo("gen-2");
        assertThat(retired.get(1).generationId()).isEqualTo("gen-1");
    }

    // ===== 可复用代际 =====

    @Test
    void findReusableGenerationFindsSuccessWithSameFingerprint() {
        SQLiteKnowledgeClaimVectorStore store = store();
        ClaimVectorGenerationManifest manifest = new ClaimVectorGenerationManifest(
                "gen-reuse", "immortal", "5.1", "fp-abc",
                "knowledge-claim-vector-v1", "knowledge-claim-text-v1",
                "test-model", 1024, null, GenerationStatus.SUCCESS,
                10, 10, "[]", Instant.now().toString(), null, null);
        store.recordBuildStart(manifest, List.of());

        Optional<ClaimVectorGenerationManifest> found = store.findReusableGeneration(
                "immortal", "5.1", "fp-abc",
                "knowledge-claim-vector-v1", "test-model");

        assertThat(found).isPresent();
        assertThat(found.get().generationId()).isEqualTo("gen-reuse");
    }

    @Test
    void findReusableReturnsEmptyForDifferentFingerprint() {
        SQLiteKnowledgeClaimVectorStore store = store();
        store.recordBuildStart(manifest("gen-diff", "SUCCESS"), List.of());

        Optional<ClaimVectorGenerationManifest> found = store.findReusableGeneration(
                "immortal", "5.1", "different-fp",
                "knowledge-claim-vector-v1", "test-model");

        assertThat(found).isEmpty();
    }

    @Test
    void findReusableReturnsEmptyForFailedGeneration() {
        SQLiteKnowledgeClaimVectorStore store = store();
        store.recordBuildStart(manifest("gen-failed", "BUILDING"), List.of());
        store.updateStatus("gen-failed", GenerationStatus.FAILED, 0, "[]");

        Optional<ClaimVectorGenerationManifest> found = store.findReusableGeneration(
                "immortal", "5.1", "default-fp",
                "knowledge-claim-vector-v1", "test-model");

        assertThat(found).isEmpty();
    }

    @Test
    void findLatestGenerationReturnsMostRecent() {
        SQLiteKnowledgeClaimVectorStore store = store();
        store.recordBuildStart(manifest("gen-early", "BUILDING"), List.of());
        // gen-late started_at 更晚（在 manifest 方法里用 Instant.now()）
        store.recordBuildStart(manifest("gen-late", "BUILDING"), List.of());

        Optional<ClaimVectorGenerationManifest> latest = store.findLatestGeneration("immortal", "5.1");
        assertThat(latest).isPresent();
        assertThat(latest.get().generationId()).isEqualTo("gen-late");
    }

    @Test
    void findActiveReturnsEmptyWhenNoActiveGeneration() {
        SQLiteKnowledgeClaimVectorStore store = store();
        store.recordBuildStart(manifest("gen-none", "SUCCESS"), List.of());
        // 不调 markActive

        assertThat(store.findActiveGeneration("immortal", "5.1")).isEmpty();
    }

    // ===== 工具 =====

    private ClaimVectorGenerationManifest manifest(String generationId, String status) {
        return new ClaimVectorGenerationManifest(
                generationId, "immortal", "5.1", "default-fp-" + generationId,
                "knowledge-claim-vector-v1", "knowledge-claim-text-v1",
                "test-model", 1024, null, GenerationStatus.valueOf(status),
                0, 0, "[]", Instant.now().toString(), null, null);
    }
}
