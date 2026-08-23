package com.example.requirementrag.knowledge.multisource.alignment;

import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.AlignmentRelation;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BusinessConcept;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.ConceptAlias;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.ConceptMember;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DriftItem;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.VersionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeCentricAlignmentStoreTest {
    @TempDir Path tempDir;

    private CodeCentricAlignmentStore store;

    @BeforeEach
    void setUp() {
        store = new CodeCentricAlignmentStore(tempDir.resolve("alignment.db").toString());
    }

    @Test
    void versionContextUpsertIsIdempotentByProjectVersionEnvironment() {
        VersionContext first = new VersionContext("vc-1", "immortal", "5.1", "immortal-game-service",
                "abc123", "staging", "ACTIVE", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        VersionContext second = new VersionContext("vc-2", "immortal", "5.1", "immortal-game-service",
                "def456", "staging", "ACTIVE", "2026-01-02T00:00:00Z", "2026-01-02T00:00:00Z");

        store.upsertVersionContext(first);
        store.upsertVersionContext(second);

        // 不同 commit 在同一环境各自保留独立上下文，不互相覆盖
        assertThat(store.listVersionContexts("immortal", "5.1")).hasSize(2);
        assertThat(store.findVersionContext("immortal", "5.1", "staging").orElseThrow().commitSha())
                .isEqualTo("def456");

        // 相同 commit 重复 upsert 仍幂等
        store.upsertVersionContext(second);
        assertThat(store.listVersionContexts("immortal", "5.1")).hasSize(2);
    }

    @Test
    void conceptAliasMemberCrudRoundTrips() {
        BusinessConcept concept = new BusinessConcept("con-1", "immortal", "param:combat.fireballcd",
                "火球冷却时间", "PARAMETER", "combat", "火球冷却", "ACTIVE", null, null);
        store.upsertConcept(concept);

        ConceptAlias alias = new ConceptAlias("cal-1", "immortal", "con-1", "Fireball_CD",
                "PARAMETER_TABLE", "SOURCE_NAME", 1.0, null);
        store.upsertAlias(alias);

        ConceptMember member = new ConceptMember("cm-1", "immortal", "con-1", "p-1",
                "PARAMETER_TABLE", "CONFIGURATION", "p-1", "Fireball_CD",
                null, null, "ev-1", "5.1", "vc-1", null);
        store.upsertMember(member);

        assertThat(store.findConceptByKey("immortal", "param:combat.fireballcd"))
                .contains(concept);
        assertThat(store.findAliases("immortal", "con-1")).extracting(ConceptAlias::alias)
                .containsExactly("Fireball_CD");
        assertThat(store.findMembers("immortal", "con-1", "5.1"))
                .extracting(ConceptMember::sourceType).containsExactly("PARAMETER_TABLE");
        assertThat(store.findMembersBySource("immortal", "PARAMETER_TABLE", "p-1", "5.1"))
                .extracting(ConceptMember::conceptId).containsExactly("con-1");
        assertThat(store.findMembers("immortal", "con-1", "5.2")).isEmpty();
    }

    @Test
    void alignmentRelationUpsertIsIdempotentByUniqueScope() {
        String now = Instant.now().toString();
        AlignmentRelation first = new AlignmentRelation("ar-1", "immortal", "5.1", "vc-1",
                "p-1", null, "PARAMETER_TABLE", null, "s-1", "CODE",
                "READS_CONFIG", "NORMALIZED_NAME_EXACT", "RULE_CONFIRMED", 0.9,
                null, "vc-1", "vc-1", "first", now, now);
        AlignmentRelation second = new AlignmentRelation("ar-2", "immortal", "5.1", "vc-1",
                "p-1", null, "PARAMETER_TABLE", null, "s-1", "CODE",
                "READS_CONFIG", "NORMALIZED_NAME_CONTAINS", "LLM_CANDIDATE", 0.5,
                null, "vc-1", "vc-1", "second", now, now);

        store.saveAlignmentRelation(first);
        store.saveAlignmentRelation(second);

        List<AlignmentRelation> relations = store.findAlignmentRelations("immortal", "5.1", "vc-1", "READS_CONFIG");
        assertThat(relations).hasSize(1);
        assertThat(relations.get(0).matchMethod()).isEqualTo("NORMALIZED_NAME_CONTAINS");
        assertThat(relations.get(0).detail()).isEqualTo("second");
    }

    @Test
    void alignmentRelationIsIsolatedByVersionContext() {
        String now = Instant.now().toString();
        AlignmentRelation staging = new AlignmentRelation("ar-staging", "immortal", "5.1", "vc-staging",
                "p-1", null, "PARAMETER_TABLE", null, "s-1", "CODE",
                "READS_CONFIG", "NORMALIZED_NAME_EXACT", "RULE_CONFIRMED", 0.9,
                null, "vc-staging", "vc-staging", "staging", now, now);
        AlignmentRelation production = new AlignmentRelation("ar-prod", "immortal", "5.1", "vc-prod",
                "p-1", null, "PARAMETER_TABLE", null, "s-1", "CODE",
                "READS_CONFIG", "NORMALIZED_NAME_EXACT", "RULE_CONFIRMED", 0.9,
                null, "vc-prod", "vc-prod", "production", now, now);

        store.saveAlignmentRelation(staging);
        store.saveAlignmentRelation(production);

        assertThat(store.findAlignmentRelations("immortal", "5.1", "vc-staging", "READS_CONFIG")).hasSize(1);
        assertThat(store.findAlignmentRelations("immortal", "5.1", "vc-prod", "READS_CONFIG")).hasSize(1);

        store.deleteAlignmentRelationsByType("immortal", "5.1", "vc-staging", "READS_CONFIG");
        assertThat(store.findAlignmentRelations("immortal", "5.1", "vc-staging", "READS_CONFIG")).isEmpty();
        assertThat(store.findAlignmentRelations("immortal", "5.1", "vc-prod", "READS_CONFIG")).hasSize(1);
    }

    @Test
    void driftItemUpsertIsIdempotentByConceptTypeAndContext() {
        String now = Instant.now().toString();
        DriftItem first = new DriftItem("di-1", "immortal", "5.1", "vc-1", "con-1", "req.fire",
                "DOCUMENT_DRIFT", "WARNING", "INTENT", "r-1", null, "10", "12",
                "first", "OPEN", now, now);
        DriftItem second = new DriftItem("di-2", "immortal", "5.1", "vc-1", "con-1", "req.fire",
                "DOCUMENT_DRIFT", "ERROR", "INTENT", "r-1", null, "10", "12",
                "second", "OPEN", now, now);

        store.saveDriftItem(first);
        store.saveDriftItem(second);

        List<DriftItem> items = store.findDriftItems("immortal", "5.1", "vc-1", "DOCUMENT_DRIFT");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).detail()).isEqualTo("second");

        DriftItem otherContext = new DriftItem("di-3", "immortal", "5.1", "vc-2", "con-1", "req.fire",
                "DOCUMENT_DRIFT", "WARNING", "INTENT", "r-1", null, "10", "12",
                "other", "OPEN", now, now);
        store.saveDriftItem(otherContext);
        assertThat(store.findDriftItems("immortal", "5.1", "vc-1", "DOCUMENT_DRIFT")).hasSize(1);
        assertThat(store.findDriftItems("immortal", "5.1", "vc-2", "DOCUMENT_DRIFT")).hasSize(1);

        store.deleteDriftItemsByType("immortal", "5.1", "vc-1", "DOCUMENT_DRIFT");
        assertThat(store.findDriftItems("immortal", "5.1", "vc-1", null)).isEmpty();
        assertThat(store.findDriftItems("immortal", "5.1", "vc-2", "DOCUMENT_DRIFT")).hasSize(1);
    }
}
