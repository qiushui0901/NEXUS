package com.example.requirementrag.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WikiKnowledgePageTest {
    @Test
    void exposesVersionedProductDevelopmentTestAndEvidenceViewsWithoutCdn() throws Exception {
        String html = new ClassPathResource("static/wiki.html")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("版本化需求 · 代码 · 测试知识库")
                .contains("id=\"projectSelect\"")
                .contains("id=\"versionSelect\"")
                .contains("['product','产品']")
                .contains("['development','开发']")
                .contains("['test','测试']")
                .contains("['evidence','证据']")
                .contains("/api/wiki/projects")
                .contains("/api/wiki/generate")
                .contains("const esc=")
                .doesNotContain("unpkg.com")
                .doesNotContain("cdn.jsdelivr.net");
    }
}
