package com.example.requirementrag.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class MonitorWorkbenchPageTest {

    @Test
    void separatesIntentGraphModeSearchAndContextSidebar() throws IOException {
        String html = new ClassPathResource("static/monitor.html")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("data-intent=\"graph\"")
                .contains("data-intent=\"plan\"")
                .contains("class=\"mode-strip\"")
                .contains("class=\"search-strip\"")
                .contains("class=\"rail-tabs\"")
                .contains("项目导览")
                .contains("开始导览")
                .contains("引用组件")
                .contains("sidebarTab === 'files'")
                .contains("loadSource(file)")
                .contains("v-bind=\"{ viewBox: graphViewBox }\"")
                .contains("/api/assistant/development-plan/stream")
                .contains("response.body.getReader()")
                .contains("new AbortController()")
                .contains("applyPlanEvent(event)")
                .contains("class=\"plan-stage\"")
                .contains("class=\"source-drawer-backdrop\"")
                .contains("<teleport to=\"body\">")
                .contains("'source-drawer', sourceFullscreen ? 'fullscreen':'flat'")
                .contains("toggleSourceFullscreen")
                .contains("closeSourceDrawer")
                .contains("event.key === 'Escape'")
                .contains("class=\"graph-floating-tools\"")
                .contains(".search-submit { width:auto; min-width:82px; height:28px; padding:0 12px; white-space:nowrap;")
                .contains("planStatus:'idle'")
                .contains(":class=\"planStatus\"")
                .contains("this.planStatus = 'running'")
                .contains("this.planStatus = 'success'")
                .contains("this.planStatus = 'error'")
                .contains(".plan-stage i.error")
                .doesNotContain("<textarea");
    }

    @Test
    void keepsSourceInsidePlanAndBindsEvidenceToEachSection() throws IOException {
        String html = new ClassPathResource("static/monitor.html")
                .getContentAsString(StandardCharsets.UTF_8);
        int loadSourceStart = html.indexOf("async loadSource(nodeOrHit)");
        int selectNodeStart = html.indexOf("selectNode(node)", loadSourceStart);
        String loadSource = html.substring(loadSourceStart, selectNodeStart);

        assertThat(loadSourceStart).isGreaterThanOrEqualTo(0);
        assertThat(selectNodeStart).isGreaterThan(loadSourceStart);
        assertThat(loadSource)
                .doesNotContain("this.intentTab = 'graph'")
                .doesNotContain("this.activeTab = 'graph'");
        assertThat(html)
                .contains("class=\"plan-chain-graph\"")
                .contains("section.relatedRules")
                .contains("hit.relation")
                .contains("hit.matchType")
                .contains("查看源码")
                .doesNotContain("<h3>相关代码点</h3>")
                .doesNotContain("<h3>相关规则片段</h3>");
    }

    @Test
    void rendersExistingAndPlannedNodesInAnIndependentPlanGraph() throws IOException {
        String html = new ClassPathResource("static/monitor.html")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("planGraph:{")
                .contains("planGraphNodes")
                .contains("planGraphEdges")
                .contains("async loadPlanGraph()")
                .contains("this.api('/api/code/graph'")
                .contains("node.planned")
                .contains(".plan-edge.planned")
                .contains("fitPlanGraph")
                .contains("togglePlanGraphFullscreen")
                .contains("focusPlanSection")
                .contains("@wheel.prevent=\"planGraphWheel\"");
    }

    @Test
    void usesOneCompactTypographyScaleAcrossThePlan() throws IOException {
        String html = new ClassPathResource("static/monitor.html")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("class=\"panel plan-view\"")
                .contains("--plan-body-size:12px")
                .contains("--plan-heading-size:13px")
                .contains("--plan-meta-size:10px")
                .contains(".plan-view .section-card")
                .contains("font-size:var(--plan-body-size)")
                .contains("font-size:var(--plan-heading-size)")
                .contains("font-size:var(--plan-meta-size)");
    }
}
