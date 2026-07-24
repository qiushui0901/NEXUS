package com.example.requirementrag.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class VersionKnowledgePageTest {
    @Test
    void exposesVersionTimelineAndSafeMultiSourceComparisonWithoutCdn() throws Exception {
        String html = new ClassPathResource("static/versions.html")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("/api/wiki/projects")
                .contains("/api/versions/manifests")
                .contains("/api/versions/compare")
                .contains("需求变化")
                .contains("代码变化")
                .contains("测试变化")
                .contains("Wiki 变化")
                .contains("没有真实执行快照")
                .contains("const esc=")
                .contains("X-API-Key")
                .contains("localStorage.getItem('nexusApiKey')")
                .contains("/wiki?")
                .doesNotContain("unpkg.com")
                .doesNotContain("cdn.jsdelivr.net");
    }

    @Test
    void redirectsHumanReadableVersionRouteToStaticPage() {
        assertThat(new VersionPageController().versionsPage()).isEqualTo("redirect:/versions.html");
    }
}
