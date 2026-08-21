package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.model.ChunkRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementGraphWindowPlannerTest {
    private final RequirementGraphWindowPlanner planner = new RequirementGraphWindowPlanner();

    @Test
    void coversLongParentWithOverlapAndPreservesTail() {
        String text = "段落一。".repeat(700) + "验收标准：库存必须回滚。";
        ChunkRecord chunk = new ChunkRecord("chunk", "requirements", "5.1", "orders.md", "parent", text,
                text, "hash", 0, 0);

        RequirementGraphWindowPlanner.Plan plan = planner.plan(chunk, 1_000, 200);

        assertThat(plan.windowCount()).isGreaterThan(1);
        assertThat(plan.coverageRatio()).isEqualTo(1.0);
        assertThat(plan.windows().get(plan.windowCount() - 1).text()).contains("库存必须回滚");
        assertThat(plan.windows()).allSatisfy(window -> assertThat(window.endOffset() - window.startOffset())
                .isEqualTo(window.text().length()));
    }

    @Test
    void emptyParentProducesEmptyPlan() {
        ChunkRecord chunk = new ChunkRecord("chunk", "requirements", "5.1", "orders.md", "parent", "", "", "hash", 0, 0);
        assertThat(planner.plan(chunk, 1_000).windows()).isEmpty();
    }
}
