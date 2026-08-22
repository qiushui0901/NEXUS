package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.EntityType;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractedEntity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractedRelation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractionInput;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractionResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RequirementGraphExtractionServiceTest {
    private final RequirementGraphProperties properties = new RequirementGraphProperties(
            true, true, true, "", 20, 30, 20_000, 2, 40, "model", "v1");
    private final RequirementGraphExtractionService service = new RequirementGraphExtractionService(
            mock(ChatClient.class), mock(RagProperties.class), properties);
    private final ExtractionInput input = new ExtractionInput(
            "orders.md", "parent-1", 0, "订单 / 取消", "取消订单", "hash",
            "玩家购买成长基金并影响库存。");

    @Test
    void normalizesAllowedTypesAndKeepsOnlyEvidenceBackedFacts() {
        ExtractionResult result = service.validate(input, new ExtractionResult(
                List.of(
                        new ExtractedEntity("e1", "business-object", "玩家", List.of("用户"),
                                "购买者", List.of("玩家购买成长基金"), 0.9),
                        new ExtractedEntity("e2", "MODULE", "库存", List.of(),
                                "库存模块", List.of("影响库存"), 0.8)),
                List.of(new ExtractedRelation("e1", "affects-module", "e2", "玩家行为影响库存",
                        List.of("玩家购买成长基金并影响库存"), 0.7)), List.of()));

        assertThat(result.entities()).extracting(ExtractedEntity::type)
                .containsExactly(EntityType.BUSINESS_OBJECT.name(), EntityType.MODULE.name());
        assertThat(result.relations()).singleElement().satisfies(relation ->
                assertThat(relation.type()).isEqualTo("AFFECTS_MODULE"));
    }

    @Test
    void rejectsRelationWithoutAnExactSourceQuote() {
        ExtractionResult result = new ExtractionResult(
                List.of(new ExtractedEntity("e1", "MODULE", "库存", List.of(), "",
                        List.of("库存"), 0.8)),
                List.of(new ExtractedRelation("e1", "AFFECTS_MODULE", "e1", "自环",
                        List.of("模型编造的句子"), 0.7)), List.of());

        assertThatThrownBy(() -> service.validate(input, result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("原文证据");
    }

    @Test
    void rejectsUnknownEntityType() {
        ExtractionResult result = new ExtractionResult(
                List.of(new ExtractedEntity("e1", "NOT_A_TYPE", "库存", List.of(), "",
                        List.of("库存"), 0.8)), List.of(), List.of());

        assertThatThrownBy(() -> service.validate(input, result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知需求实体类型");
    }

    @Test
    void rejectsSelfLoopRelation() {
        ExtractionResult result = new ExtractionResult(
                List.of(new ExtractedEntity("e1", "MODULE", "库存", List.of(), "",
                        List.of("库存"), 0.8)),
                List.of(new ExtractedRelation("e1", "AFFECTS_MODULE", "e1", "库存影响库存",
                        List.of("影响库存"), 0.7)), List.of());

        assertThatThrownBy(() -> service.validate(input, result))
                .isInstanceOf(RequirementGraphException.class)
                .satisfies(exception -> assertThat(((RequirementGraphException) exception).code())
                        .isEqualTo("GRAPH_SCHEMA_INVALID"))
                .hasMessageContaining("自环");
    }

    @Test
    void rejectsDuplicateRelation() {
        ExtractionResult result = new ExtractionResult(
                List.of(new ExtractedEntity("e1", "FEATURE", "成长基金", List.of(), "",
                                List.of("玩家购买成长基金"), 0.9),
                        new ExtractedEntity("e2", "MODULE", "库存", List.of(), "",
                                List.of("影响库存"), 0.8)),
                List.of(
                        new ExtractedRelation("e1", "AFFECTS_MODULE", "e2", "成长基金影响库存",
                                List.of("玩家购买成长基金并影响库存"), 0.8),
                        new ExtractedRelation("e1", "AFFECTS_MODULE", "e2", "成长基金影响库存（重复）",
                                List.of("玩家购买成长基金并影响库存"), 0.7)),
                List.of());

        assertThatThrownBy(() -> service.validate(input, result))
                .isInstanceOf(RequirementGraphException.class)
                .hasMessageContaining("重复关系");
    }
}
