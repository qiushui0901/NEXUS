package com.example.requirementrag.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WikiKnowledgePageTest {
    @Test
    void exposesEvidenceBoundRequirementDevelopmentTestingAndLegacyViewsWithoutCdn() throws Exception {
        String html = new ClassPathResource("static/wiki.html")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("版本化需求 · 代码 · 测试知识库")
                .contains("id=\"projectSelect\"")
                .contains("id=\"versionSelect\"")
                .contains("['requirements', '需求']")
                .contains("['development', '开发']")
                .contains("['testing', '测试']")
                .contains("['evidence', '证据']")
                .contains("requirementSources")
                .contains("processSteps")
                .contains("codeEntries")
                .contains("acceptanceCriteria")
                .contains("没有真实执行快照")
                .contains("static evidence")
                .contains("/api/wiki/projects")
                .contains("/api/wiki/generate")
                .contains("/versions")
                .contains("href=\"/knowledge\"")
                .contains("new URLSearchParams(location.search).get('projectId')")
                .contains("new URLSearchParams(location.search).get('version')")
                .contains("new URLSearchParams(location.search).get('featureId')")
                .contains("pendingFeatureId")
                .contains("const esc =")
                .contains("legacyCode.map")
                .doesNotContain("unpkg.com")
                .doesNotContain("cdn.jsdelivr.net");
    }
}
