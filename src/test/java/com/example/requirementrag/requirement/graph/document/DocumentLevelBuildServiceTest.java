package com.example.requirementrag.requirement.graph.document;

import com.example.requirementrag.requirement.graph.RequirementGraphWindowPlanner;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.CrossWindowRelation;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.DocumentLevelBuildResult;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.LogicalUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentLevelBuildServiceTest {
    @TempDir Path tempDir;

    private DocumentLevelBuildService service(RequirementDocumentStructureStore store) {
        return new DocumentLevelBuildService(store, new DocumentStructureExtractor(), new LogicalUnitPlanner(),
                new CrossWindowIntegrator(), new BuildFingerprintFactory(), new RequirementGraphWindowPlanner());
    }

    @Test
    void buildsStructureUnitsCrossWindowRelationsAndFingerprint() {
        RequirementDocumentStructureStore store =
                new RequirementDocumentStructureStore(tempDir.resolve("doc.db").toString());
        DocumentLevelBuildService buildService = service(store);

        String text = """
                # 第一章 总则
                REQ-001 系统应当支持火球冷却配置。
                REQ-002 系统应当引用 REQ-001 的冷却时间，并确保一致。

                | 字段 | 默认值 | 单位 |
                | 冷却 | 12 | 秒 |
                """;

        DocumentLevelBuildResult result = buildService.build("doc-1", "5.1", "rev-1", text);

        assertThat(result.structure()).isNotEmpty();
        assertThat(result.logicalUnits()).extracting(LogicalUnit::unitType).contains("REQUIREMENT", "TABLE");
        assertThat(result.relations()).extracting(CrossWindowRelation::relationType).contains("REFERENCES");
        assertThat(result.relations()).filteredOn(r -> "COMPOSITE_SUPPORTED".equals(r.supportMode())).isNotEmpty();
        assertThat(result.fingerprint().fingerprint()).isNotBlank();

        // 持久化后可查询
        assertThat(store.findStructureNodes("doc-1", "5.1")).isNotEmpty();
        assertThat(store.findLogicalUnits("doc-1", "rev-1")).isNotEmpty();
        assertThat(store.findEvidenceBundles("doc-1", "5.1")).isNotEmpty();
    }

    @Test
    void unavailableReferenceIsNotPublishedAsConfirmed() {
        RequirementDocumentStructureStore store =
                new RequirementDocumentStructureStore(tempDir.resolve("doc2.db").toString());
        DocumentLevelBuildService buildService = service(store);

        String text = """
                REQ-001 系统引用 REQ-999（不存在）作为前置条件。
                """;
        DocumentLevelBuildResult result = buildService.build("doc-2", "5.1", "rev-1", text);

        List<CrossWindowRelation> relations = result.relations();
        assertThat(relations).singleElement().satisfies(r -> {
            assertThat(r.supportMode()).isEqualTo("UNAVAILABLE");
            assertThat(r.status()).isEqualTo("UNRESOLVED");
        });
    }
}
