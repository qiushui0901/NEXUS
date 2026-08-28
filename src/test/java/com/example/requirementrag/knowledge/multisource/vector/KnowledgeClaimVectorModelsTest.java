package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.ClaimVectorGenerationInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeClaimVectorModelsTest {

    // ===== Point ID =====

    @Test
    void pointIdIsDeterministic() {
        String id1 = KnowledgeClaimVectorModels.deterministicPointId(
                "immortal", "5.1", "claim-abc", "knowledge-claim-vector-v1");
        String id2 = KnowledgeClaimVectorModels.deterministicPointId(
                "immortal", "5.1", "claim-abc", "knowledge-claim-vector-v1");

        assertThat(id1).isEqualTo(id2);
        // Qdrant v1.15+ 只接受无符号整数或标准 UUID；64-hex 会被 400 拒绝
        assertThat(id1).hasSize(36);
        assertThat(UUID.fromString(id1).version()).isEqualTo(5);
    }

    @Test
    void pointIdChangesWithSchemaVersion() {
        String v1 = KnowledgeClaimVectorModels.deterministicPointId(
                "immortal", "5.1", "claim-abc", "knowledge-claim-vector-v1");
        String v2 = KnowledgeClaimVectorModels.deterministicPointId(
                "immortal", "5.1", "claim-abc", "knowledge-claim-vector-v2");

        assertThat(v1).isNotEqualTo(v2);
    }

    @Test
    void pointIdChangesWithClaimId() {
        String a = KnowledgeClaimVectorModels.deterministicPointId(
                "immortal", "5.1", "claim-a", "knowledge-claim-vector-v1");
        String b = KnowledgeClaimVectorModels.deterministicPointId(
                "immortal", "5.1", "claim-b", "knowledge-claim-vector-v1");

        assertThat(a).isNotEqualTo(b);
    }

    // ===== Fingerprint =====

    @Test
    void fingerprintIsStableUnderReordering() {
        List<ClaimVectorGenerationInput> ordered = List.of(
                input("claim-1", "dv-1", "hash-1", "2026-01-01"),
                input("claim-2", "dv-1", "hash-2", "2026-01-02"),
                input("claim-3", "dv-2", "hash-3", "2026-01-03"));
        List<ClaimVectorGenerationInput> shuffled = List.of(
                input("claim-3", "dv-2", "hash-3", "2026-01-03"),
                input("claim-1", "dv-1", "hash-1", "2026-01-01"),
                input("claim-2", "dv-1", "hash-2", "2026-01-02"));

        String fp1 = KnowledgeClaimVectorModels.computeInputFingerprint(
                ordered, "v1", "text-v1", "test-model", 1024);
        String fp2 = KnowledgeClaimVectorModels.computeInputFingerprint(
                shuffled, "v1", "text-v1", "test-model", 1024);

        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    void fingerprintChangesForTextInput() {
        List<ClaimVectorGenerationInput> original = List.of(
                input("claim-1", "dv-1", "hash-1", "2026-01-01"));
        List<ClaimVectorGenerationInput> modified = List.of(
                input("claim-1", "dv-1", "hash-changed", "2026-01-01"));

        String fp1 = fingerprint(original);
        String fp2 = fingerprint(modified);

        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    void fingerprintChangesForSchemaVersion() {
        List<ClaimVectorGenerationInput> inputs = List.of(input("claim-1", "dv-1", "hash-1", "2026-01-01"));

        String fp1 = KnowledgeClaimVectorModels.computeInputFingerprint(
                inputs, "v1", "text-v1", "test-model", 1024);
        String fp2 = KnowledgeClaimVectorModels.computeInputFingerprint(
                inputs, "v2", "text-v1", "test-model", 1024);

        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    void fingerprintChangesForComposerVersion() {
        List<ClaimVectorGenerationInput> inputs = List.of(input("claim-1", "dv-1", "hash-1", "2026-01-01"));

        String fp1 = KnowledgeClaimVectorModels.computeInputFingerprint(
                inputs, "v1", "text-v1", "test-model", 1024);
        String fp2 = KnowledgeClaimVectorModels.computeInputFingerprint(
                inputs, "v1", "text-v2", "test-model", 1024);

        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    void fingerprintChangesForEmbeddingModel() {
        List<ClaimVectorGenerationInput> inputs = List.of(input("claim-1", "dv-1", "hash-1", "2026-01-01"));

        String fp1 = KnowledgeClaimVectorModels.computeInputFingerprint(
                inputs, "v1", "text-v1", "model-a", 1024);
        String fp2 = KnowledgeClaimVectorModels.computeInputFingerprint(
                inputs, "v1", "text-v1", "model-b", 1024);

        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    void fingerprintChangesForDimension() {
        List<ClaimVectorGenerationInput> inputs = List.of(input("claim-1", "dv-1", "hash-1", "2026-01-01"));

        String fp1 = KnowledgeClaimVectorModels.computeInputFingerprint(
                inputs, "v1", "text-v1", "test-model", 1024);
        String fp2 = KnowledgeClaimVectorModels.computeInputFingerprint(
                inputs, "v1", "text-v1", "test-model", 768);

        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    void fingerprintChangesForSemanticEnhancementIdentity() {
        List<ClaimVectorGenerationInput> inputs = List.of(input("claim-1", "dv-1", "hash-1", "2026-01-01"));

        String disabled = KnowledgeClaimVectorModels.computeInputFingerprint(
                inputs, "v1", "text-v2", "test-model", 1024,
                "ACTIVE_DOC", false, "gpt-5.6-luna");
        String enabled = KnowledgeClaimVectorModels.computeInputFingerprint(
                inputs, "v1", "text-v2", "test-model", 1024,
                "ACTIVE_DOC", true, "gpt-5.6-luna");
        String otherModel = KnowledgeClaimVectorModels.computeInputFingerprint(
                inputs, "v1", "text-v2", "test-model", 1024,
                "ACTIVE_DOC", true, "other-model");

        assertThat(disabled).isNotEqualTo(enabled);
        assertThat(enabled).isNotEqualTo(otherModel);
    }

    @Test
    void fingerprintChangesForUpdatedAt() {
        List<ClaimVectorGenerationInput> original = List.of(
                input("claim-1", "dv-1", "hash-1", "2026-01-01"));
        List<ClaimVectorGenerationInput> modified = List.of(
                input("claim-1", "dv-1", "hash-1", "2026-02-01"));

        assertThat(fingerprint(original)).isNotEqualTo(fingerprint(modified));
    }

    @Test
    void emptyInputsProduceStableFingerprint() {
        String fp1 = KnowledgeClaimVectorModels.computeInputFingerprint(
                List.of(), "v1", "text-v1", "test-model", 1024);
        String fp2 = KnowledgeClaimVectorModels.computeInputFingerprint(
                List.of(), "v1", "text-v1", "test-model", 1024);

        assertThat(fp1).isEqualTo(fp2);
    }

    private static String fingerprint(List<ClaimVectorGenerationInput> inputs) {
        return KnowledgeClaimVectorModels.computeInputFingerprint(
                inputs, "v1", "text-v1", "test-model", 1024);
    }

    private static ClaimVectorGenerationInput input(String claimId, String documentVersionId,
                                                     String textHash, String updatedAt) {
        return new ClaimVectorGenerationInput("gen-1", claimId, documentVersionId, textHash, updatedAt);
    }
}
