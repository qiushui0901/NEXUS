package com.example.requirementrag.requirement.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequirementGraphQualityGateTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void syntheticCorpusHasAtLeastTwentyStableCasesAndPassesInitialGate() throws Exception {
        String content = new ClassPathResource("requirement-graph/gold-corpus.jsonl")
                .getContentAsString(StandardCharsets.UTF_8);
        var cases = Arrays.stream(content.split("\\R"))
                .filter(line -> !line.isBlank())
                .map(line -> read(line))
                .toList();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(20);
        assertThat(cases).extracting(GoldCase::id).doesNotHaveDuplicates();
        assertThat(cases).allSatisfy(item -> {
            assertThat(item.evidence()).isNotBlank();
            assertThat(item.subject()).isNotBlank();
            assertThat(item.predicate()).isNotBlank();
        });
        RequirementGraphQualityGate.assertPass(new RequirementGraphQualityGate.Report(
                1.0, 1.0, 1.0, 0.0, 0, 0.0, 1.0));
    }

    @Test
    void gateRejectsUnsupportedPublishedClaims() {
        assertThatThrownBy(() -> RequirementGraphQualityGate.assertPass(
                new RequirementGraphQualityGate.Report(1.0, 1.0, 1.0, 0.01, 0, 0, 1.0)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("未受支持");
    }

    private GoldCase read(String line) {
        try {
            return mapper.readValue(line, GoldCase.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid synthetic graph case", exception);
        }
    }

    private record GoldCase(String id, String kind, String subject, String predicate,
                            String object, String evidence) {
    }
}
