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
        String now = Instant.now().toString();
        VersionContext first = new VersionContext("vc-1", "immortal", "5.1", "immortal-game-service",
                "abc123", "staging", "ACTIVE", now, now);
        VersionContext second = new VersionContext("vc-2", "immortal", "5.1", "immortal-game-service",
                "def456", "staging", "ACTIVE", now, now);

        store.upsertVersionContext(first);
        store.upsertVersionContext(second);

        assertThat(store.findVersionContext("immortal", "5.1", "staging")).isPresent();
        assertThat(store.findVersionContext("immortal", "5.1", "staging").orElseThrow().commitSha())
                .isEqualTo("def456");
        assertThat(store.listVersionContexts("immortal", "5.1")).hasSize(1);
    }

    @Test
    void conceptAliasMemberCrudRoundTrips() {
        BusinessConcept concept = new BusinessConcept("con-1", "immortal", "param.combat.fireball.cooldown",
                "火球冷却时间", "PARAMETER", "combat", "火球冷却", "ACTIVE", null, null);
        store.upsertConcept(concept);

        ConceptAlias alias = new ConceptAlias("cal-1", "immortal", "con-1", "Fireball_CD",
                "PARAMETER_TABLE", "SOURCE_NAME", 1.0, null);
        store.upsertAlias(alias);

        ConceptMember member = new ConceptMember("cm-1", "immortal", "con-1", "p-1",
                "PARAMETER_TABLE", "CONFIGURATION", "p-1", "Fireball_CD",
                null, null, "ev-1", null);
        store.upsertMember(member);

        assertThat(store.findConceptByKey("immortal", "param.combat.fireball.cooldown"))
                .contains(concept);
        assertThat(store.findAliases("immortal", "con-1")).extracting(ConceptAlias::alias)
                .containsExactly("Fireball_CD");
        assertThat(store.findMembers("immortal", "con-1")).extracting(ConceptMember::sourceType)
                .containsExactly("PARAMETER_TABLE");
        assertThat(store.findMembersBySource("immortal", "PARAMETER_TABLE", "p-1"))
                .extracting(ConceptMember::conceptId).containsExactly("con-1");
    }

    @Test
    void alignmentRelationUpsertIsIdempotentByUniqueScope() {
        String now = Instant.now().toString();
        AlignmentRelation first = new AlignmentRelation("ar-1", "immortal", "5.1",
                "p-1", null, "PARAMETER_TABLE", null, "s-1", "CODE",
                "READS_CONFIG", "NORMALIZED_NAME_EXACT", "RULE_CONFIRMED", 0.9,
                null, "vc-1", "vc-1", "first", now, now);
        AlignmentRelation second = new AlignmentRelation("ar-2", "immortal", "5.1",
                "p-1", null, "PARAMETER_TABLE", null, "s-1", "CODE",
                "READS_CONFIG", "NORMALIZED_NAME_CONTAINS", "LLM_CANDIDATE", 0.5,
                null, "vc-1", "vc-1", "second", now, now);

        store.saveAlignmentRelation(first);
        store.saveAlignmentRelation(second);

        List<AlignmentRelation> relations = store.findAlignmentRelations("immortal", "5.1", "READS_CONFIG");
        assertThat(relations).hasSize(1);
        assertThat(relations.get(0).matchMethod()).isEqualTo("NORMALIZED_NAME_CONTAINS");
        assertThat(relations.get(0).detail()).isEqualTo("second");
    }

    @Test
    void driftItemUpsertIsIdempotentByConceptAndType() {
        String now = Instant.now().toString();
        DriftItem first = new DriftItem("di-1", "immortal", "5.1", "con-1", "req.fire",
                "DOCUMENT_DRIFT", "WARNING", "INTENT", "r-1", null, "10", "12",
                "first", "OPEN", now, now);
        DriftItem second = new DriftItem("di-2", "immortal", "5.1", "con-1", "req.fire",
                "DOCUMENT_DRIFT", "ERROR", "INTENT", "r-1", null, "10", "12",
                "second", "OPEN", now, now);

        store.saveDriftItem(first);
        store.saveDriftItem(second);

        List<DriftItem> items = store.findDriftItems("immortal", "5.1", "DOCUMENT_DRIFT");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).detail()).isEqualTo("second");

        store.deleteDriftItemsByType("immortal", "5.1", "DOCUMENT_DRIFT");
        assertThat(store.findDriftItems("immortal", "5.1", null)).isEmpty();
    }
}