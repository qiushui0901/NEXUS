package com.example.requirementrag.knowledge.multisource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地构建入口：对已导入的 immortal 数据生成跨源总实体关系图。
 *
 * <p>只在显式开启系统属性时才运行：{@code -Dgraph.build=true}。
 */
@EnabledIfSystemProperty(named = "graph.build", matches = "true")
class KnowledgeGraphBuildIT {

    @Test
    void buildsCrossSourceEntityGraphForImportedData() {
        ObjectMapper objectMapper = new ObjectMapper();
        MultiSourceKnowledgeStore store = new MultiSourceKnowledgeStore(
                "data/multi-source-knowledge.db", objectMapper);
        KnowledgeGraphBuildService service = new KnowledgeGraphBuildService(store);

        KnowledgeGraphBuildService.GraphBuildResult result = service.build("immortal", "5.1");

        System.out.println("[KnowledgeGraph] entities=" + result.entities() + ", relations=" + result.relations());
        assertThat(result.entities()).isGreaterThan(0);
        assertThat(result.relations()).isGreaterThan(0);
    }
}