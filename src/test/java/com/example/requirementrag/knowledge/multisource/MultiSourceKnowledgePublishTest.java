package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocumentVersion;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        store.upsertDocumentVersion(documentVersion("dv-1", "DRAFT"));
        store.publishDocumentVersion("fengshen", "5.1", "dv-1");
        assertThat(store.activeDocumentVersion("fengshen", "5.1")).contains("dv-1");

        store.upsertDocumentVersion(documentVersion("dv-2", "DRAFT"));
        store.publishDocumentVersion("fengshen", "5.1", "dv-2");
        assertThat(store.activeDocumentVersion("fengshen", "5.1")).contains("dv-2");

        store.rollbackActiveVersion("fengshen", "5.1", "dv-1");
        assertThat(store.activeDocumentVersion("fengshen", "5.1")).contains("dv-1");
    }

    @Test
    void businessVersionsAreIsolatedInManifest() {
        store.upsertDocumentVersion(documentVersion("dv-51", "DRAFT"));
        store.publishDocumentVersion("fengshen", "5.1", "dv-51");
        store.upsertDocumentVersion(new KnowledgeDocumentVersion(
                "dv-52", "doc-dv-52", "fengshen", "5.2", "hash-dv-52",
                "parser-v1", "extraction-v1", "sha1", "DRAFT",
                "2025-01-01T00:00:00Z", null));
        store.publishDocumentVersion("fengshen", "5.2", "dv-52");

        assertThat(store.activeDocumentVersion("fengshen", "5.1")).contains("dv-51");
        assertThat(store.activeDocumentVersion("fengshen", "5.2")).contains("dv-52");
    }

    // ── 高（Review 3）：目标文档版本存在性/归属校验 + 原子发布 ─────────────

    @Test
    void publishRejectsUnknownOrForeignDocumentVersion() {
        // 不存在于任何项目
        assertThatThrownBy(() -> store.publishDocumentVersion("fengshen", "5.1", "ghost-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或不属于");
        assertThat(store.activeDocumentVersion("fengshen", "5.1")).isEmpty();

        // 属于其他项目（跨项目 ID 不得写入 manifest）
        store.upsertDocumentVersion(new KnowledgeDocumentVersion(
                "dv-other-project", "doc-x", "immortal", "5.1", "hash-x",
                "parser-v1", "extraction-v1", "sha1", "PUBLISHED",
                "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z"));
        assertThatThrownBy(() -> store.publishDocumentVersion("fengshen", "5.1", "dv-other-project"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或不属于");
    }

    @Test
    void rollbackRejectsUnknownOrForeignDocumentVersion() {
        store.upsertDocumentVersion(documentVersion("dv-1", "DRAFT"));
        store.publishDocumentVersion("fengshen", "5.1", "dv-1");

        assertThatThrownBy(() -> store.rollbackActiveVersion("fengshen", "5.1", "ghost-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或不属于");
        // manifest 未被破坏
        assertThat(store.activeDocumentVersion("fengshen", "5.1")).contains("dv-1");
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

    // ── High：关系证据必须绑定端点 Claim（同版本无关 Evidence 不得判 CONFIRMED） ──

    @Test
    void relationEvidenceMustBelongToEndpointClaims() {
        // dv-1 含关系端点 c-a → c-b（REQUIREMENT）；dv-2 为并行文档
        seedDraftDocumentWithClaim("dv-1", "c-a");
        seedDraftDocumentWithClaim("dv-1", "c-b");
        store.publishDocumentVersion("fengshen", "5.1", "dv-1");
        seedDraftDocumentWithClaim("dv-2", "c-other");
        store.publishDocumentVersion("fengshen", "5.1", "dv-2");

        // 合法：绑定端点 c-a、同文档同来源（REQUIREMENT/dv-1）
        store.saveEvidence(new KnowledgeEvidence("ev-bound", "dv-1", "fengshen",
                SourceType.REQUIREMENT, "doc#A1", "excerpt", "h1",
                1, 2, null, null, null, null, null, null, "2025-01-01T00:00:00Z"));
        store.linkClaimEvidence("c-a", "ev-bound", "SUPPORTS");
        // 错误绑定：绑定端点 c-a 但文档是并行 dv-2（文档版本不一致）
        store.saveEvidence(new KnowledgeEvidence("ev-cross-doc", "dv-2", "fengshen",
                SourceType.REQUIREMENT, "doc#B1", "excerpt2", "h2",
                1, 2, null, null, null, null, null, null, "2025-01-01T00:00:00Z"));
        store.linkClaimEvidence("c-a", "ev-cross-doc", "SUPPORTS");
        // 错误绑定：绑定端点 c-a、同文档但来源类型不一致（TEST_RESULT vs REQUIREMENT）
        store.saveEvidence(new KnowledgeEvidence("ev-wrong-type", "dv-1", "fengshen",
                SourceType.TEST_RESULT, "doc#C1", "excerpt3", "h3",
                1, 2, null, null, null, null, null, null, "2025-01-01T00:00:00Z"));
        store.linkClaimEvidence("c-a", "ev-wrong-type", "SUPPORTS");
        // 与端点无关：属于并行文档、绑定 c-other
        store.saveEvidence(new KnowledgeEvidence("ev-unrelated", "dv-2", "fengshen",
                SourceType.REQUIREMENT, "doc#D1", "excerpt4", "h4",
                1, 2, null, null, null, null, null, null, "2025-01-01T00:00:00Z"));
        store.linkClaimEvidence("c-other", "ev-unrelated", "SUPPORTS");

        // Claim-Claim：绑定端点且同文档同来源 → CONFIRMED
        assertThat(store.isPublishedEvidenceForRelation("fengshen", "5.1", "ev-bound", "c-a", "c-b"))
                .isTrue();
        // Claim → 代码符号（target 为空）：绑定 source Claim 即可 → CONFIRMED
        assertThat(store.isPublishedEvidenceForRelation("fengshen", "5.1", "ev-bound", "c-a", null))
                .isTrue();
        // 错误绑定端点但文档不一致 / 来源类型不一致 → 拒绝
        assertThat(store.isPublishedEvidenceForRelation("fengshen", "5.1", "ev-cross-doc", "c-a", "c-b"))
                .isFalse();
        assertThat(store.isPublishedEvidenceForRelation("fengshen", "5.1", "ev-wrong-type", "c-a", "c-b"))
                .isFalse();
        // 与端点无关 → 拒绝
        assertThat(store.isPublishedEvidenceForRelation("fengshen", "5.1", "ev-unrelated", "c-a", "c-b"))
                .isFalse();
    }

    // ── Medium：实体层同态（Agent 层=全部 PUBLISHED 并行文档；active 单文档仅供向量投影） ──

    @Test
    void publishedSiblingDocClaimsVisibleInAllVariantButNotActiveBound() {
        // active manifest 现指向 dv-2（单文档）：active 绑定查询只见 dv-2；
        // 实体层 All 变体必须覆盖两个并行已发布文档的 Claim
        seedDraftDocumentWithClaim("dv-1", "c-1");
        store.publishDocumentVersion("fengshen", "5.1", "dv-1");
        seedDraftDocumentWithClaim("dv-2", "c-sibling");
        store.publishDocumentVersion("fengshen", "5.1", "dv-2");

        assertThat(store.activeDocumentVersion("fengshen", "5.1")).contains("dv-2");
        // active-manifest 绑定查询：只见 active 单文档（dv-2）
        assertThat(store.findPublishedClaimIdsByIds("fengshen", List.of("c-1"))).isEmpty();
        assertThat(store.findPublishedClaimIdsByIds("fengshen", List.of("c-sibling"))).contains("c-sibling");
        // 实体层同态：并行已发布文档全部可见（图扩展/向量命中映射用）
        assertThat(store.findPublishedClaimIdsByIdsAll("fengshen", List.of("c-1"))).contains("c-1");
        assertThat(store.findPublishedClaimIdsByIdsAll("fengshen", List.of("c-sibling"))).contains("c-sibling");
        // 版本收窄的全量水化同样覆盖兄弟文档（Qdrant payload 可伪造，SQLite 以业务版本为权威）
        assertThat(store.findPublishedClaimsByIdsAll("fengshen", "5.1", List.of("c-sibling")))
                .extracting(KnowledgeClaimRecord::claimId).contains("c-sibling");
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
    void siblingDocumentsCoexistPublishedAndRollbackSwitchesProjectionOnly() {
        // 兄弟文档（不同 document_id，同一业务版本下的并行来源）：发布互不降级，
        // 回滚只切换 manifest 的投影目标
        seedDraftDocumentWithClaim("dv-1", "c-1");
        seedDraftDocumentWithClaim("dv-2", "c-2");
        store.publishDocumentVersion("fengshen", "5.1", "dv-1");
        store.publishDocumentVersion("fengshen", "5.1", "dv-2");
        // 兄弟共存：dv-1 不被 dv-2 降级，两个都保持 PUBLISHED
        assertThat(store.findDocumentVersionById("dv-1").orElseThrow().status()).isEqualTo("PUBLISHED");
        assertThat(store.findDocumentVersionById("dv-2").orElseThrow().status()).isEqualTo("PUBLISHED");
        assertThat(store.activeDocumentVersion("fengshen", "5.1")).contains("dv-2");

        store.rollbackActiveVersion("fengshen", "5.1", "dv-1");

        // 回滚只切投影目标；兄弟文档状态不变
        assertThat(store.activeDocumentVersion("fengshen", "5.1")).contains("dv-1");
        assertThat(store.findDocumentVersionById("dv-1").orElseThrow().status()).isEqualTo("PUBLISHED");
        assertThat(store.findDocumentVersionById("dv-2").orElseThrow().status()).isEqualTo("PUBLISHED");
        // 投影精确绑定 manifest：回滚到 dv-1 → 只有 c-1
        assertThat(store.findPublishedClaimsByProjectVersionPage("fengshen", "5.1", 100, 0))
                .extracting(KnowledgeClaimRecord::claimId)
                .containsExactly("c-1");
    }

    @Test
    void publishNewVersionOfSameDocumentReplacesOld() {
        // 同一 document_id 的新版本（替换语义保留）：dv-old/dv-new 同属 doc-shared
        store.upsertDocumentVersion(new KnowledgeDocumentVersion(
                "dv-old", "doc-shared", "fengshen", "5.1", "hash-dv-old",
                "parser-v1", "extraction-v1", "sha1", "DRAFT",
                "2025-01-01T00:00:00Z", null));
        store.upsertDocumentVersion(new KnowledgeDocumentVersion(
                "dv-new", "doc-shared", "fengshen", "5.1", "hash-dv-new",
                "parser-v1", "extraction-v1", "sha1", "DRAFT",
                "2025-01-01T00:00:00Z", null));
        store.publishDocumentVersion("fengshen", "5.1", "dv-old");
        store.publishDocumentVersion("fengshen", "5.1", "dv-new");

        // 同文档新版本发布 → 旧版本降回 DRAFT，active 切到新版本
        assertThat(store.findDocumentVersionById("dv-old").orElseThrow().status()).isEqualTo("DRAFT");
        assertThat(store.findDocumentVersionById("dv-new").orElseThrow().status()).isEqualTo("PUBLISHED");
        assertThat(store.activeDocumentVersion("fengshen", "5.1")).contains("dv-new");
    }

    // ── 高（Review 3）：投影精确绑定 manifest 的 document_version_id ─────────

    @Test
    void projectionBindsToManifestDocumentVersionEvenWhenOtherDocPublished() {
        // 同业务版本下存在两个 PUBLISHED 文档版本（模拟发布中断/历史残留），
        // 但 manifest 只指向 dv-1——dv-2 的 Claim 不得进入投影。
        seedPublishedDocumentWithClaim("dv-1", "c-1");
        seedPublishedDocumentWithClaim("dv-2", "c-2");
        store.publishDocumentVersion("fengshen", "5.1", "dv-1");

        // 即使 dv-2 仍为 PUBLISHED，投影也只含 manifest 绑定的 dv-1 的 Claim
        assertThat(store.findDocumentVersionById("dv-2").orElseThrow().status()).isEqualTo("PUBLISHED");
        assertThat(store.findPublishedClaimsByProjectVersionPage("fengshen", "5.1", 100, 0))
                .extracting(KnowledgeClaimRecord::claimId)
                .containsExactly("c-1");
    }

    private void seedPublishedDocumentWithClaim(String dvId, String claimId) {
        store.upsertDocumentVersion(documentVersion(dvId, "PUBLISHED"));
        store.saveClaim(new KnowledgeClaimRecord(
                claimId, "fengshen", dvId, SourceType.REQUIREMENT, Authority.PRIMARY,
                "fk#" + claimId, "subject-" + claimId, "必须支持", "30秒", "", "", "ACTIVE",
                0.9, null, null, "RULE", "run-1",
                "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z"));
    }
}