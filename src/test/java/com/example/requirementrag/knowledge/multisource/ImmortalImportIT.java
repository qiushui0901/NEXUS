package com.example.requirementrag.knowledge.multisource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地导入入口：把 document/immortal 四类知识导入 data/multi-source-knowledge.db。
 *
 * <p>只在显式开启系统属性时才运行：{@code -Dimmortal.import=true}。
 */
@EnabledIfSystemProperty(named = "immortal.import", matches = "true")
class ImmortalImportIT {

    @Test
    void importsImmortalKnowledgeIntoLocalStore() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MultiSourceKnowledgeStore store = new MultiSourceKnowledgeStore(
                "data/multi-source-knowledge.db", objectMapper);
        ImmortalKnowledgeImporter importer = new ImmortalKnowledgeImporter(store);
        Path root = Path.of(System.getProperty("immortal.root", "/Users/user/Documents/immortal"));

        ImmortalKnowledgeImporter.ImportSummary summary = importer.importAll("immortal", "5.1", root);

        System.out.println("[ImmortalImport] summary: " + summary);
        if (summary.documents() == 0) {
            // 缓存命中：全部文件内容未变化，不重复导入
            assertThat(summary.evidences() + summary.parameters() + summary.doubts()
                    + summary.testCases() + summary.requirementClaims()).isZero();
        } else {
            assertThat(summary.evidences() + summary.parameters() + summary.doubts()
                    + summary.testCases() + summary.requirementClaims()).isGreaterThan(0);
        }
    }
}