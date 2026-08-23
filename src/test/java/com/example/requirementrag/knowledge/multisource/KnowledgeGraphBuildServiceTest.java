package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocument;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocumentVersion;
import com.example.requirementrag.knowledge.multisource.KnowledgeGraphModels.KnowledgeEntity;
import com.example.requirementrag.knowledge.multisource.KnowledgeGraphModels.KnowledgeEntityRelation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeGraphBuildServiceTest {
    @TempDir Path tempDir;

    private MultiSourceKnowledgeStore store;
    private KnowledgeGraphBuildService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        store = new MultiSourceKnowledgeStore(tempDir.resolve("graph.db").toString(), objectMapper);
        store.registerDocument(new KnowledgeDocument("doc-1", "immortal", SourceType.REQUIREMENT,
                "prd", "prd", "file:///prd", Authority.PRIMARY, null));
        store.upsertDocumentVersion(new KnowledgeDocumentVersion("dv-1", "doc-1", "immortal", "5.1",
                "hash", "immortal", "v1", null, "DRAFT", null, null));

        store.saveClaim(claim("req-1", SourceType.REQUIREMENT, Authority.PRIMARY, "英雄", "document", "英雄系统",
                "immortal|5.1|英雄|英雄|document"));
        store.saveClaim(claim("param-1", SourceType.PARAMETER_TABLE, Authority.PRIMARY, "name", "value", "hero",
                "immortal|5.1|ImmortalHero|name|value"));
        store.saveClaim(claim("tc-1", SourceType.TEST_CASE, Authority.SECONDARY, "英雄", "expectedResult", "可招募",
                "immortal|5.1|英雄|英雄|expectedResult"));
        store.saveClaim(claim("doubt-1", SourceType.DOUBT, Authority.PRIMARY, "英雄", "question", "是否平衡",
                "immortal|5.1|英雄|英雄|question"));

        service = new KnowledgeGraphBuildService(store);
    }

    @Test
    void buildsModuleLevelEntityGraphWithRuleRelations() {
        KnowledgeGraphBuildService.GraphBuildResult result = service.build("immortal", "5.1");

        assertThat(result.entities()).isGreaterThanOrEqualTo(4);
        assertThat(result.relations()).isGreaterThanOrEqualTo(2);

        List<KnowledgeEntity> entities = store.findEntities("immortal", "5.1");
        List<KnowledgeEntityRelation> relations = store.findEntityRelations("immortal", "5.1");
        assertThat(entities).extracting(KnowledgeEntity::name)
                .contains("英雄", "prd");
        assertThat(relations).extracting(KnowledgeEntityRelation::relationType)
                .contains("VERIFIES", "RAISES_DOUBT");
    }

    @Test
    void persistsGraphAndCanBeQueried() {
        service.build("immortal", "5.1");

        assertThat(store.findEntities("immortal", "5.1")).isNotEmpty();
        assertThat(store.findEntityRelations("immortal", "5.1")).isNotEmpty();
    }

    private KnowledgeClaimRecord claim(String id, SourceType sourceType, Authority authority,
                                       String subject, String predicate, String object, String factKey) {
        return new KnowledgeClaimRecord(id, "immortal", "dv-1", sourceType, authority, factKey,
                subject, predicate, object, "TEXT", null, "SUPPORTED",
                null, null, null, "RULE", null, null, null);
    }
}