package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocumentVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MultiSourceKnowledgePublishTest {
    @TempDir Path tempDir;

    private MultiSourceKnowledgeStore store;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        store = new MultiSourceKnowledgeStore(tempDir.resolve("publish.db").toString(), objectMapper);
    }

    @Test
    void publishAndRollbackActiveDocumentVersion() {
        assertThat(store.activeDocumentVersion("fengshen", "5.1")).isEmpty();

        store.publishDocumentVersion("fengshen", "5.1", "dv-1");
        assertThat(store.activeDocumentVersion("fengshen", "5.1")).contains("dv-1");

        store.publishDocumentVersion("fengshen", "5.1", "dv-2");
        assertThat(store.activeDocumentVersion("fengshen", "5.1")).contains("dv-2");

        store.rollbackActiveVersion("fengshen", "5.1", "dv-1");
        assertThat(store.activeDocumentVersion("fengshen", "5.1")).contains("dv-1");
    }

    @Test
    void businessVersionsAreIsolatedInManifest() {
        store.publishDocumentVersion("fengshen", "5.1", "dv-51");
        store.publishDocumentVersion("fengshen", "5.2", "dv-52");

        assertThat(store.activeDocumentVersion("fengshen", "5.1")).contains("dv-51");
        assertThat(store.activeDocumentVersion("fengshen", "5.2")).contains("dv-52");
    }

    // ── 高（Review 2）：发布同步标记资料版本 PUBLISHED ──────────────────

    private KnowledgeDocumentVersion documentVersion(String dvId, String status) {
        return new KnowledgeDocumentVersion(
                dvId, "doc-" + dvId, "fengshen", "5.1", "hash-" + dvId,
                "parser-v1", "extraction-v1", "sha1", status,
                "2025-01-01T00:00:00Z", null);
    }

    private void seedDraftDocumentWithClaim(String dvId, String claimId) {
        store.upsertDocumentVersion(documentVersion(dvId, "DRAFT"));
        store.saveClaim(new KnowledgeClaimRecord(
                claimId, "fengshen", dvId, SourceType.REQUIREMENT, Authority.PRIMARY,
                "fk#" + claimId, "subject-" + claimId, "必须支持", "30秒", "", "", "ACTIVE",
                0.9, null, null, "RULE", "run-1",
                "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z"));
    }

    @Test
    void publishMarksDocumentVersionPublished() {
        store.upsertDocumentVersion(documentVersion("dv-1", "DRAFT"));
        assertThat(store.findDocumentVersionById("dv-1").orElseThrow().status()).isEqualTo("DRAFT");

        store.publishDocumentVersion("fengshen", "5.1", "dv-1");

        assertThat(store.findDocumentVersionById("dv-1").orElseThrow().status()).isEqualTo("PUBLISHED");
        assertThat(store.findDocumentVersionById("dv-1").orElseThrow().publishedAt()).isNotNull();
    }

    @Test
    void publishedOnlyProjectionExcludesDraftAndIsolatesScopeAndVersion() {
        // 同版本：dv-1 已发布，dv-2 仍是 DRAFT
        seedDraftDocumentWithClaim("dv-1", "c-1");
        seedDraftDocumentWithClaim("dv-2", "c-2");
        // 不同业务版本：dv-other 同 scope 不同版本
        store.upsertDocumentVersion(new KnowledgeDocumentVersion(
                "dv-other", "doc-other", "fengshen", "5.0", "hash-other",
                "parser-v1", "extraction-v1", "sha1", "PUBLISHED",
                "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z"));
        store.saveClaim(new KnowledgeClaimRecord(
                "c-other", "fengshen", "dv-other", SourceType.REQUIREMENT, Authority.PRIMARY,
                "fk#other", "subject-other", "必须支持", "30秒", "", "", "ACTIVE",
                0.9, null, null, "RULE", "run-1",
                "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z"));

        // 全部 DRAFT 时已发布投影为空（治理边界：待审核资料不得投影）
        assertThat(store.findPublishedClaimsByProjectVersionPage("fengshen", "5.1", 100, 0))
                .isEmpty();
        assertThat(store.findClaimsByProjectVersionPage("fengshen", "5.1", 100, 0))
                .hasSize(2);

        store.publishDocumentVersion("fengshen", "5.1", "dv-1");

        // 只投影已发布版本 dv-1 的 Claim；DRAFT 的 dv-2 与不同版本的 dv-other 均排除
        var published = store.findPublishedClaimsByProjectVersionPage("fengshen", "5.1", 100, 0);
        assertThat(published).extracting(KnowledgeClaimRecord::claimId)
                .containsExactly("c-1");
    }

    @Test
    void rollbackDemotesPreviousPublisherAndPromotesTarget() {
        seedDraftDocumentWithClaim("dv-1", "c-1");
        seedDraftDocumentWithClaim("dv-2", "c-2");
        store.publishDocumentVersion("fengshen", "5.1", "dv-1");
        store.publishDocumentVersion("fengshen", "5.1", "dv-2");
        // dv-1 被 dv-2 替换后应回到 DRAFT
        assertThat(store.findDocumentVersionById("dv-1").orElseThrow().status()).isEqualTo("DRAFT");

        store.rollbackActiveVersion("fengshen", "5.1", "dv-1");

        assertThat(store.findDocumentVersionById("dv-1").orElseThrow().status()).isEqualTo("PUBLISHED");
        assertThat(store.findDocumentVersionById("dv-2").orElseThrow().status()).isEqualTo("DRAFT");
        // 投影回到已发布者：只含 dv-1 的 Claim
        assertThat(store.findPublishedClaimsByProjectVersionPage("fengshen", "5.1", 100, 0))
                .extracting(KnowledgeClaimRecord::claimId)
                .containsExactly("c-1");
    }
}