package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.ClaimVectorGenerationManifest;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.GenerationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaimVectorQualityGateTest {

    @TempDir
    Path tempDir;

    private SQLiteKnowledgeClaimVectorStore sqliteStore;
    private KnowledgeClaimVectorQdrantStore qdrantStore;
    private ClaimVectorShadowEvaluator shadowEvaluator;
    private KnowledgeClaimVectorProperties properties;
    private ClaimVectorQualityGate gate;

    @BeforeEach
    void setUp() {
        sqliteStore = mock(SQLiteKnowledgeClaimVectorStore.class);
        qdrantStore = mock(KnowledgeClaimVectorQdrantStore.class);
        shadowEvaluator = mock(ClaimVectorShadowEvaluator.class);
        properties = new KnowledgeClaimVectorProperties(
                true, true, true, true,
                "knowledge_claims_live", "knowledge-claim-vector-v1", "knowledge-claim-text-v1",
                200, 3, 32, 3, 2, tempDir.resolve("test-gate.db").toString());
        gate = new ClaimVectorQualityGate(sqliteStore, qdrantStore, shadowEvaluator, properties);
    }

    private ClaimVectorGenerationManifest activeManifest(int indexed, int expected) {
        return new ClaimVectorGenerationManifest(
                "gen-active", "proj-1", "v1", "fp-1",
                "knowledge-claim-vector-v1", "knowledge-claim-text-v1",
                "test-model", 8, "knowledge_claims_live-123456",
                GenerationStatus.ACTIVE, indexed, expected, "[]",
                "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z",
                "2025-01-01T00:00:00Z");
    }

    // ── no active generation ────────────────────────────────────────────

    @Test
    void noActiveGenerationFailsImmediately() {
        when(sqliteStore.findActiveGeneration("proj-1", "v1"))
                .thenReturn(Optional.empty());

        ClaimVectorQualityGate.QualityGateReport report = gate.check("proj-1", "v1");

        assertThat(report.readyToPublish()).isFalse();
        assertThat(report.failedCount()).isEqualTo(1);
        assertThat(report.checks()).hasSize(1);
        assertThat(report.checks().get(0).name()).isEqualTo("ACTIVE_GENERATION");
        verify(qdrantStore, never()).aliasTarget(anyString());
    }

    // ── all checks pass ────────────────────────────────────────────────

    @Test
    void allChecksPassReadyToPublish() {
        ClaimVectorGenerationManifest manifest = activeManifest(100, 100);
        when(sqliteStore.findActiveGeneration("proj-1", "v1"))
                .thenReturn(Optional.of(manifest));
        when(qdrantStore.aliasTarget("knowledge_claims_live-proj-1-v1"))
                .thenReturn("knowledge_claims_live-123456");
        when(qdrantStore.countPointsIfAvailable("knowledge_claims_live-123456"))
                .thenReturn(100L);
        ClaimVectorShadowEvaluator.ScopeStats stats = new ClaimVectorShadowEvaluator.ScopeStats(
                "proj-1", "v1", 25, 300, 200, 150, 10, 5000);
        when(shadowEvaluator.scopeMetric("proj-1", "v1"))
                .thenReturn(stats);

        ClaimVectorQualityGate.QualityGateReport report = gate.check("proj-1", "v1");

        assertThat(report.readyToPublish()).isTrue();
        assertThat(report.passedCount()).isEqualTo(5);
        assertThat(report.failedCount()).isZero();
    }

    // ── point count mismatch ───────────────────────────────────────────

    @Test
    void pointCountMismatchFails() {
        ClaimVectorGenerationManifest manifest = activeManifest(95, 100);
        when(sqliteStore.findActiveGeneration("proj-1", "v1"))
                .thenReturn(Optional.of(manifest));
        when(qdrantStore.aliasTarget(anyString())).thenReturn("knowledge_claims_live-123456");
        when(qdrantStore.countPointsIfAvailable(anyString())).thenReturn(95L);
        when(shadowEvaluator.scopeMetric(anyString(), anyString())).thenReturn(
                new ClaimVectorShadowEvaluator.ScopeStats("proj-1", "v1", 25, 300, 200, 150, 10, 5000));

        ClaimVectorQualityGate.QualityGateReport report = gate.check("proj-1", "v1");

        assertThat(report.readyToPublish()).isFalse();
        assertThat(report.checks().stream().filter(c -> c.name().equals("POINT_COUNT")).allMatch(c -> !c.passed())).isTrue();
    }

    // ── alias mismatch ──────────────────────────────────────────────────

    @Test
    void aliasMismatchFails() {
        ClaimVectorGenerationManifest manifest = activeManifest(100, 100);
        when(sqliteStore.findActiveGeneration("proj-1", "v1"))
                .thenReturn(Optional.of(manifest));
        when(qdrantStore.aliasTarget("knowledge_claims_live-proj-1-v1"))
                .thenReturn("knowledge_claims_live-old");
        when(qdrantStore.countPointsIfAvailable(anyString())).thenReturn(100L);
        when(shadowEvaluator.scopeMetric(anyString(), anyString())).thenReturn(
                new ClaimVectorShadowEvaluator.ScopeStats("proj-1", "v1", 25, 300, 200, 150, 10, 5000));

        ClaimVectorQualityGate.QualityGateReport report = gate.check("proj-1", "v1");

        assertThat(report.readyToPublish()).isFalse();
        assertThat(report.checks().stream().filter(c -> c.name().equals("ALIAS_HEALTH")).allMatch(c -> !c.passed())).isTrue();
    }

    // ── physical count mismatch ─────────────────────────────────────────

    @Test
    void physicalCountMismatchFails() {
        ClaimVectorGenerationManifest manifest = activeManifest(100, 100);
        when(sqliteStore.findActiveGeneration("proj-1", "v1"))
                .thenReturn(Optional.of(manifest));
        when(qdrantStore.aliasTarget(anyString())).thenReturn("knowledge_claims_live-123456");
        when(qdrantStore.countPointsIfAvailable("knowledge_claims_live-123456"))
                .thenReturn(98L);
        when(shadowEvaluator.scopeMetric(anyString(), anyString())).thenReturn(
                new ClaimVectorShadowEvaluator.ScopeStats("proj-1", "v1", 25, 300, 200, 150, 10, 5000));

        ClaimVectorQualityGate.QualityGateReport report = gate.check("proj-1", "v1");

        assertThat(report.readyToPublish()).isFalse();
        assertThat(report.checks().stream().filter(c -> c.name().equals("PHYSICAL_CONSISTENCY")).allMatch(c -> !c.passed())).isTrue();
    }

    // ── Qdrant unavailable ──────────────────────────────────────────────

    @Test
    void qdrantUnavailableFailsPhysicalConsistency() {
        ClaimVectorGenerationManifest manifest = activeManifest(100, 100);
        when(sqliteStore.findActiveGeneration("proj-1", "v1"))
                .thenReturn(Optional.of(manifest));
        when(qdrantStore.aliasTarget(anyString())).thenReturn("knowledge_claims_live-123456");
        when(qdrantStore.countPointsIfAvailable(anyString())).thenReturn(-1L);
        when(shadowEvaluator.scopeMetric(anyString(), anyString())).thenReturn(
                new ClaimVectorShadowEvaluator.ScopeStats("proj-1", "v1", 25, 300, 200, 150, 10, 5000));

        ClaimVectorQualityGate.QualityGateReport report = gate.check("proj-1", "v1");

        assertThat(report.readyToPublish()).isFalse();
        assertThat(report.checks().stream().filter(c -> c.name().equals("PHYSICAL_CONSISTENCY")).allMatch(c -> !c.passed())).isTrue();
    }

    // ── insufficient shadow data ───────────────────────────────────────

    @Test
    void insufficientShadowDataFails() {
        ClaimVectorGenerationManifest manifest = activeManifest(100, 100);
        when(sqliteStore.findActiveGeneration("proj-1", "v1"))
                .thenReturn(Optional.of(manifest));
        when(qdrantStore.aliasTarget(anyString())).thenReturn("knowledge_claims_live-123456");
        when(qdrantStore.countPointsIfAvailable(anyString())).thenReturn(100L);
        when(shadowEvaluator.scopeMetric(anyString(), anyString())).thenReturn(
                new ClaimVectorShadowEvaluator.ScopeStats("proj-1", "v1", 5, 50, 40, 30, 2, 1000));

        ClaimVectorQualityGate.QualityGateReport report = gate.check("proj-1", "v1");

        assertThat(report.readyToPublish()).isFalse();
        assertThat(report.checks().stream().filter(c -> c.name().equals("SHADOW_DATA")).allMatch(c -> !c.passed())).isTrue();
    }

    // ── shadow data null ────────────────────────────────────────────────

    @Test
    void noShadowDataFails() {
        ClaimVectorGenerationManifest manifest = activeManifest(100, 100);
        when(sqliteStore.findActiveGeneration("proj-1", "v1"))
                .thenReturn(Optional.of(manifest));
        when(qdrantStore.aliasTarget(anyString())).thenReturn("knowledge_claims_live-123456");
        when(qdrantStore.countPointsIfAvailable(anyString())).thenReturn(100L);
        when(shadowEvaluator.scopeMetric(anyString(), anyString())).thenReturn(null);

        ClaimVectorQualityGate.QualityGateReport report = gate.check("proj-1", "v1");

        assertThat(report.readyToPublish()).isFalse();
        assertThat(report.checks().stream().filter(c -> c.name().equals("SHADOW_DATA")).allMatch(c -> !c.passed())).isTrue();
    }

    // ── shadow disabled skips check ─────────────────────────────────────

    @Test
    void shadowDisabledSkipsShadowCheck() {
        properties = new KnowledgeClaimVectorProperties(
                true, true, true, false,
                "knowledge_claims_live", "knowledge-claim-vector-v1", "knowledge-claim-text-v1",
                200, 3, 32, 3, 2, tempDir.resolve("test-no-shadow.db").toString());
        gate = new ClaimVectorQualityGate(sqliteStore, qdrantStore, shadowEvaluator, properties);
        ClaimVectorGenerationManifest manifest = activeManifest(100, 100);
        when(sqliteStore.findActiveGeneration("proj-1", "v1"))
                .thenReturn(Optional.of(manifest));
        when(qdrantStore.aliasTarget(anyString())).thenReturn("knowledge_claims_live-123456");
        when(qdrantStore.countPointsIfAvailable(anyString())).thenReturn(100L);

        ClaimVectorQualityGate.QualityGateReport report = gate.check("proj-1", "v1");

        assertThat(report.readyToPublish()).isTrue();
        assertThat(report.checks()).hasSize(4);
        assertThat(report.checks().stream().noneMatch(c -> c.name().equals("SHADOW_DATA"))).isTrue();
    }

    // ── report summary fields ───────────────────────────────────────────

    @Test
    void reportSummaryFieldsCorrect() {
        ClaimVectorGenerationManifest manifest = activeManifest(100, 100);
        when(sqliteStore.findActiveGeneration("proj-1", "v1"))
                .thenReturn(Optional.of(manifest));
        when(qdrantStore.aliasTarget(anyString())).thenReturn("knowledge_claims_live-123456");
        when(qdrantStore.countPointsIfAvailable(anyString())).thenReturn(100L);
        when(shadowEvaluator.scopeMetric(anyString(), anyString())).thenReturn(
                new ClaimVectorShadowEvaluator.ScopeStats("proj-1", "v1", 25, 300, 200, 150, 10, 5000));

        ClaimVectorQualityGate.QualityGateReport report = gate.check("proj-1", "v1");

        assertThat(report.projectId()).isEqualTo("proj-1");
        assertThat(report.businessVersion()).isEqualTo("v1");
        assertThat(report.passedCount()).isEqualTo(5);
        assertThat(report.failedCount()).isZero();
    }
}
