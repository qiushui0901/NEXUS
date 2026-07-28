package com.example.requirementrag.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.requirementrag.model.DevelopmentPlanStreamEvent;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class DevelopmentPlanStreamParserTest {

    private final DevelopmentPlanStreamParser parser = new DevelopmentPlanStreamParser(new ObjectMapper());

    @Test
    void parsesSplitAndMultipleNdjsonEventsInOrder() {
        assertThat(parser.accept("{\"type\":\"summary\",\"payload\":{\"text\":\"先看"))
                .isEmpty();

        List<DevelopmentPlanStreamEvent> events = parser.accept("入口\",\"evidenceIds\":[\"requirement:req-1\"]},\"message\":\"生成概要\"}\n"
                + "{\"type\":\"constraint\",\"payload\":{\"text\":\"保证幂等\"}}\n");

        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo("summary");
        assertThat(events.get(0).sequence()).isEqualTo(1);
        assertThat(events.get(0).payload().get("text").asText()).isEqualTo("先看入口");
        assertThat(events.get(0).payload().path("evidenceIds").get(0).asText())
                .isEqualTo("requirement:req-1");
        assertThat(events.get(1).type()).isEqualTo("constraint");
        assertThat(events.get(1).sequence()).isEqualTo(2);
    }

    @Test
    void skipsBlankFencesAndInvalidLinesWithoutPoisoningNextEvent() {
        List<DevelopmentPlanStreamEvent> events = parser.accept("```json\n\nnot-json\n"
                + "{\"type\":\"risk\",\"payload\":{\"text\":\"重复发奖\"}}\n```\n");

        assertThat(events).singleElement()
                .satisfies(event -> {
                    assertThat(event.type()).isEqualTo("risk");
                    assertThat(event.payload().get("text").asText()).isEqualTo("重复发奖");
                });
    }

    @Test
    void finishParsesLastEventWithoutTrailingNewline() {
        parser.accept("{\"type\":\"completed\",\"payload\":{}}");

        assertThat(parser.finish()).singleElement()
                .extracting(DevelopmentPlanStreamEvent::type)
                .isEqualTo("completed");
        assertThat(parser.finish()).isEmpty();
    }
}
