package com.example.requirementrag.requirement.semantic;

import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationInput;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 固定 JSON fixture 回归：覆盖主体、条件、事件、数值、单位、否定、范围、
 * 时间、IN 列表、缺失上下文与空抽取场景，全部必须通过服务端校验。
 */
class RequirementSemanticFixturesTest {
    private record Fixture(String name, String rawText, SemanticAnnotationResult result) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RequirementSemanticAnnotationValidator validator =
            new RequirementSemanticAnnotationValidator(
                    RequirementSemanticAnnotationValidatorTest.properties());

    @Test
    void allFixturesPassServerSideValidation() throws Exception {
        List<Fixture> fixtures = loadFixtures();
        assertThat(fixtures).hasSizeGreaterThanOrEqualTo(10);

        for (Fixture fixture : fixtures) {
            SemanticAnnotationInput input = new SemanticAnnotationInput(
                    "p1", "doc", "5.1", "file.md|parent-1|0", "parent-1", null,
                    0, 0, fixture.rawText().length(),
                    "file.md", 0, "", "", fixture.rawText(), "hash");

            SemanticAnnotationResult validated = validator.validate(input, fixture.result());

            assertThat(validated.entities().size())
                    .as("fixture %s 实体数量应保持一致", fixture.name())
                    .isEqualTo(fixture.result().entities().size());
            assertThat(validated.claims().size())
                    .as("fixture %s Claim 数量应保持一致", fixture.name())
                    .isEqualTo(fixture.result().claims().size());
            // 每条 Claim 至少有一个可回查原文的证据（子串校验在 Validator 内已强制执行）。
            validated.claims().forEach(claim ->
                    assertThat(fixture.rawText().contains(claim.evidenceQuote()))
                            .as("fixture %s 的 Claim 证据必须回查原文", fixture.name())
                            .isTrue());
        }
    }

    private List<Fixture> loadFixtures() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/requirement-semantic-fixtures.json")) {
            assertThat(stream).as("fixture 资源必须存在").isNotNull();
            return objectMapper.readValue(stream,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Fixture.class));
        }
    }
}
