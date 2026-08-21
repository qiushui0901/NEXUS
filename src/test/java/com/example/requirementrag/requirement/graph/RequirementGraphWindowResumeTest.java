package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.model.ChunkRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementGraphWindowResumeTest {
    @Test
    void windowPlanUsesStableIdsForResume() {
        RequirementGraphWindowPlanner planner = new RequirementGraphWindowPlanner();
        String text = "订单取消会回滚库存。".repeat(300);
        ChunkRecord chunk = new ChunkRecord("chunk", "requirements", "2.0", "orders.md", "parent", text,
                text, "hash", 0, 0);
        var first = planner.plan(chunk, 1_000, 200);
        var second = planner.plan(chunk, 1_000, 200);
        assertThat(second.windows().stream().map(RequirementGraphWindow::id).toList())
                .containsExactlyElementsOf(first.windows().stream().map(RequirementGraphWindow::id).toList());
    }
}
