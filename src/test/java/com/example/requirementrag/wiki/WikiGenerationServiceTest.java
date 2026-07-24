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
    void generatesVersionedJsonAndMarkdownThatRepositoryCanBrowse() throws Exception {
        Path source = temp.resolve("sources");
        Path root = temp.resolve("wiki");
        Files.createDirectories(source);
        Files.writeString(source.resolve("game-v5.1.json"), sourceJson(false));
        ObjectMapper mapper = new ObjectMapper();
        WikiProperties properties = new WikiProperties(root.toString(), source.toString());
        WikiRepository repository = new WikiRepository(mapper, properties);
        WikiGenerationService service = new WikiGenerationService(mapper, properties, repository);

        var result = service.generate("game", "5.1");

        assertThat(result.pageCount()).isEqualTo(2);
        assertThat(root.resolve("game/5.1/index.json")).isRegularFile();
        assertThat(root.resolve("game/5.1/pages/grow-fund.md")).content()
                .contains("# 成长基金")
                .contains("GrowFundService.buy")
                .doesNotContain("denseVector");
        assertThat(repository.listProjects()).singleElement().satisfies(project -> {
            assertThat(project.projectId()).isEqualTo("game");
            assertThat(project.versions()).containsExactly("5.1");
        });
        assertThat(repository.getPage("game", "5.1", "grow-discount").codeSymbols())
                .containsExactly("GrowDiscountService.doBuy");
    }

    @Test
    void rejectsDuplicateFeatureIdsAndUnsafeIdentifiers() throws Exception {
        Path source = temp.resolve("sources");
        Path root = temp.resolve("wiki");
        Files.createDirectories(source);
        Files.writeString(source.resolve("game-v5.1.json"), sourceJson(true));
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
        String invalid = sourceJson(false).replaceFirst(
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
        String invalid = sourceJson(false).replace(
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

    private String sourceJson(boolean duplicate) {
        String secondId = duplicate ? "grow-fund" : "grow-discount";
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
                      "featureId": "grow-fund",
                      "title": "成长基金",
                      "category": "成长",
                      "introducedVersion": "5.1",
                      "status": "CODE_VERIFIED",
                      "aliases": [],
                      "summary": "独立功能",
                      "productRules": [],
                      "codeSymbols": ["GrowFundService.buy"],
                      "testPoints": [],
                      "risks": [],
                      "relations": [],
                      "evidence": []
                    },
                    {
                      "featureId": "%s",
                      "title": "成长特价礼包",
                      "category": "成长",
                      "introducedVersion": "5.0",
                      "status": "CODE_VERIFIED",
                      "aliases": [],
                      "summary": "既有功能",
                      "productRules": [],
                      "codeSymbols": ["GrowDiscountService.doBuy"],
                      "testPoints": [],
                      "risks": [],
                      "relations": [],
                      "evidence": []
                    }
                  ]
                }
                """.formatted(secondId);
    }
}
