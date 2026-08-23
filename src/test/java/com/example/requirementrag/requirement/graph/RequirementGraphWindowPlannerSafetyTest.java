package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.model.ChunkRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementGraphWindowPlannerSafetyTest {

    private final RequirementGraphWindowPlanner planner = new RequirementGraphWindowPlanner();

    @Test
    void safeOptionsEnforceMinWindowAndMinProgress() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            text.append("第").append(i).append("条 需求 REQ-").append(1000 + i)
                    .append(" 系统应当支持跨章节引用。\n");
        }
        ChunkRecord chunk = new ChunkRecord("c-1", "doc-1", "5.1", "req.md",
                "p-1", text.toString(), "", "hash", 0, 0);

        RequirementGraphWindowPlanner.Plan plan = planner.plan(chunk, 1000,
                RequirementGraphWindowPlanner.PlanOptions.safe());
        List<RequirementGraphWindow> windows = plan.windows();

        assertThat(windows).isNotEmpty();
        for (int i = 0; i < windows.size(); i++) {
            RequirementGraphWindow window = windows.get(i);
            int length = window.endOffset() - window.startOffset();
            if (window.endOffset() < plan.sourceChars()) {
                assertThat(length).isGreaterThanOrEqualTo(120);
            }
            if (i > 0) {
                int progress = window.startOffset() - windows.get(i - 1).startOffset();
                assertThat(progress).isGreaterThanOrEqualTo(200);
            }
        }
        assertThat(plan.coverageRatio()).isGreaterThan(0.9);
    }

    @Test
    void maxWindowCountKeepsTailWithoutDroppingContent() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            text.append("内容").append(i).append("0123456789。");
        }
        ChunkRecord chunk = new ChunkRecord("c-2", "doc-2", "5.1", "req.md",
                "p-2", text.toString(), "", "hash2", 0, 0);

        RequirementGraphWindowPlanner.Plan plan = planner.plan(chunk, 1000,
                new RequirementGraphWindowPlanner.PlanOptions(50, 100, 2, 200, true));

        // maxWindowCount=2 时：w0 + w1 + 合并尾部 = 3 个窗口，且覆盖完整
        assertThat(plan.windows()).hasSize(3);
        assertThat(plan.windows().get(2).endOffset()).isEqualTo(plan.sourceChars());
        assertThat(plan.coverageRatio()).isEqualTo(1.0);
    }
}
