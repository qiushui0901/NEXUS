package com.example.requirementrag.wiki;

import com.example.requirementrag.config.WikiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WikiGenerationServiceTest {
    @TempDir
    Path temp;

    @Test
    void keepsLegacySchemaReadableAndAddsTruthfulMissingTestState() throws Exception {
        Path source = temp.resolve("sources");
        Path root = temp.resolve("wiki");
        Files.createDirectories(source);
        Files.writeString(source.resolve("game-v5.1.json"), legacySourceJson(false));
        ObjectMapper mapper = new ObjectMapper();
        WikiProperties properties = new WikiProperties(root.toString(), source.toString());
        WikiRepository repository = new WikiRepository(mapper, properties);
        WikiGenerationService service = new WikiGenerationService(mapper, properties, repository);

        var result = service.generate("game", "5.1");

        assertThat(result.pageCount()).isEqualTo(2);
        assertThat(root.resolve("game/5.1/index.json")).isRegularFile();
        assertThat(root.resolve("game/5.1/pages/supply-records.md")).content()
                .contains("# 物资记录")
                .contains("SupplyRecordService.query")
                .contains("没有真实执行快照")
                .doesNotContain("denseVector");
        assertThat(repository.listProjects()).singleElement().satisfies(project -> {
            assertThat(project.projectId()).isEqualTo("game");
            assertThat(project.versions()).containsExactly("5.1");
        });
        var page = repository.getPage("game", "5.1", "unit-return-rule");
        assertThat(page.codeSymbols()).containsExactly("UnitReturnService.resolveTarget");
        assertThat(page.testKnowledge().summary()).isEqualTo("没有真实执行快照");
        assertThat(page.requirementSources()).isEmpty();
    }

    @Test
    void generatesStructuredSchemaVersionTwoKnowledge() throws Exception {
        Path source = temp.resolve("structured-sources");
        Path root = temp.resolve("structured-wiki");
        Files.createDirectories(source);
        Files.writeString(source.resolve("game-v5.1.json"), structuredSourceJson());
        ObjectMapper mapper = new ObjectMapper();
        WikiProperties properties = new WikiProperties(root.toString(), source.toString());
        WikiRepository repository = new WikiRepository(mapper, properties);
        WikiGenerationService service = new WikiGenerationService(mapper, properties, repository);

        service.generate("game", "5.1");

        var page = repository.getPage("game", "5.1", "supply-records");
        assertThat(page.requirementSources()).singleElement().satisfies(requirement -> {
            assertThat(requirement.filename()).isEqualTo("物资记录.html");
            assertThat(requirement.contentHash()).isEqualTo("hash-1");
        });
        assertThat(page.processSteps()).containsExactly("打开记录入口", "查看获取和消耗记录");
        assertThat(page.codeEntries()).singleElement().satisfies(code ->
                assertThat(code.filePath()).isEqualTo("src/SupplyRecordService.java"));
        assertThat(page.quality().realTestExecution()).isFalse();
        assertThat(root.resolve("game/5.1/pages/supply-records.md")).content()
                .contains("## 业务规则")
                .contains("## 处理流程")
                .contains("## 数据与配置影响")
                .contains("## 异常与边界条件")
                .contains("## 测试执行状态");
    }

    @Test
    void rejectsDuplicateFeatureIdsAndUnsafeIdentifiers() throws Exception {
        Path source = temp.resolve("sources");
        Path root = temp.resolve("wiki");
        Files.createDirectories(source);
        Files.writeString(source.resolve("game-v5.1.json"), legacySourceJson(true));
        ObjectMapper mapper = new ObjectMapper();
        WikiProperties properties = new WikiProperties(root.toString(), source.toString());
        WikiGenerationService service = new WikiGenerationService(mapper, properties,
                new WikiRepository(mapper, properties));

        assertThatThrownBy(() -> service.generate("game", "5.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复 featureId");
        assertThatThrownBy(() -> service.generate("../game", "5.1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRelationsToMissingFeatures() throws Exception {
        Path source = temp.resolve("sources");
        Path root = temp.resolve("wiki");
        Files.createDirectories(source);
        String invalid = legacySourceJson(false).replaceFirst(
                "\\\"relations\\\": \\[\\]",
                "\\\"relations\\\": [{\\\"targetFeatureId\\\":\\\"missing\\\","
                        + "\\\"type\\\":\\\"related\\\",\\\"label\\\":\\\"缺失功能\\\","
                        + "\\\"description\\\":\\\"\\\"}]");
        Files.writeString(source.resolve("game-v5.1.json"), invalid);
        ObjectMapper mapper = new ObjectMapper();
        WikiProperties properties = new WikiProperties(root.toString(), source.toString());
        WikiGenerationService service = new WikiGenerationService(mapper, properties,
                new WikiRepository(mapper, properties));

        assertThatThrownBy(() -> service.generate("game", "5.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("关联了不存在的 featureId: missing");
    }

    @Test
    void rejectsVectorAndCredentialFieldsInSourceDefinitions() throws Exception {
        Path source = temp.resolve("sources");
        Path root = temp.resolve("wiki");
        Files.createDirectories(source);
        String invalid = legacySourceJson(false).replace(
                "\"schemaVersion\": 1,",
                "\"schemaVersion\": 1,\n                  \"denseVector\": [0.1, 0.2],");
        Files.writeString(source.resolve("game-v5.1.json"), invalid);
        ObjectMapper mapper = new ObjectMapper();
        WikiProperties properties = new WikiProperties(root.toString(), source.toString());
        WikiGenerationService service = new WikiGenerationService(mapper, properties,
                new WikiRepository(mapper, properties));

        assertThatThrownBy(() -> service.generate("game", "5.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得包含向量、Qdrant 运行数据或凭据字段");
    }

    private String legacySourceJson(boolean duplicate) {
        String secondId = duplicate ? "supply-records" : "unit-return-rule";
        return """
                {
                  "schemaVersion": 1,
                  "projectId": "game",
                  "projectName": "Game",
                  "version": "5.1",
                  "requirementVersion": "5.1",
                  "baseCodeCommit": "base",
                  "codeCommit": "head",
                  "generatedAt": "2026-07-24T00:00:00+08:00",
                  "pages": [
                    {
                      "featureId": "supply-records",
                      "title": "物资记录",
                      "category": "记录与通知",
                      "introducedVersion": "5.1",
                      "status": "CODE_VERIFIED",
                      "aliases": [],
                      "summary": "展示资源获取与消耗记录",
                      "productRules": [],
                      "codeSymbols": ["SupplyRecordService.query"],
                      "testPoints": [],
                      "risks": [],
                      "relations": [],
                      "evidence": []
                    },
                    {
                      "featureId": "%s",
                      "title": "部队返回规则",
                      "category": "战斗与活动",
                      "introducedVersion": "5.1",
                      "status": "CODE_VERIFIED",
                      "aliases": [],
                      "summary": "调整指定场景的返回目标",
                      "productRules": [],
                      "codeSymbols": ["UnitReturnService.resolveTarget"],
                      "testPoints": [],
                      "risks": [],
                      "relations": [],
                      "evidence": []
                    }
                  ]
                }
                """.formatted(secondId);
    }

    private String structuredSourceJson() {
        return """
                {
                  "schemaVersion": 2,
                  "projectId": "game",
                  "projectName": "Game",
                  "version": "5.1",
                  "requirementVersion": "5.1",
                  "baseCodeCommit": "base",
                  "codeCommit": "head",
                  "generatedAt": "2026-07-27T00:00:00+08:00",
                  "pages": [{
                    "featureId": "supply-records",
                    "title": "物资记录",
                    "category": "记录与通知",
                    "introducedVersion": "5.1",
                    "status": "CODE_VERIFIED",
                    "aliases": [],
                    "summary": "展示资源获取与消耗记录",
                    "requirementSources": [{
                      "documentId": "requirements",
                      "entryId": "entry-1",
                      "filename": "物资记录.html",
                      "version": "5.1",
                      "location": "parentOrder=1",
                      "contentHash": "hash-1",
                      "verificationStatus": "SOURCE_CAPTURED"
                    }],
                    "productRules": ["只展示当前活动周期内的记录"],
                    "processSteps": ["打开记录入口", "查看获取和消耗记录"],
                    "codeEntries": [{
                      "role": "业务服务",
                      "filePath": "src/SupplyRecordService.java",
                      "symbol": "SupplyRecordService.query",
                      "commit": "head",
                      "changeType": "ADDED",
                      "verificationStatus": "VERIFIED"
                    }],
                    "codeSymbols": ["SupplyRecordService.query"],
                    "dataImpacts": ["活动结束后清空记录"],
                    "boundaryConditions": ["无新记录时不显示提醒"],
                    "acceptanceCriteria": ["确认记录内容与来源一致"],
                    "testPoints": ["没有真实执行快照；以下为验收建议"],
                    "testKnowledge": {
                      "executionStatus": "NOT_AVAILABLE",
                      "executionReference": "",
                      "summary": "没有真实执行快照",
                      "cases": []
                    },
                    "versionChange": {
                      "changeType": "ADDED",
                      "baseVersion": "5.0",
                      "version": "5.1",
                      "summary": "5.1 新增"
                    },
                    "quality": {
                      "reviewStatus": "PENDING_REVIEW",
                      "requirementEvidenceCount": 1,
                      "codeEvidenceCount": 1,
                      "realTestExecution": false,
                      "missing": ["真实测试执行快照"]
                    },
                    "risks": [],
                    "relations": [],
                    "evidence": []
                  }]
                }
                """;
    }
}
