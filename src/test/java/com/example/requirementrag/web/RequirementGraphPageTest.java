package com.example.requirementrag.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementGraphPageTest {
    @Test
    void exposesEvidenceFirstGraphReviewWorkbenchWithoutExternalFrontendDependencies() throws Exception {
        String html = resource("static/requirement-graph.html");
        String app = resource("static/assets/requirement-graph.js");
        String css = resource("static/assets/requirement-graph.css");

        assertThat(html)
                .contains("NEXUS · 需求语义图")
                .contains("data-nexus-shell data-page=\"requirement-graph\"")
                .contains("/assets/requirement-graph.js")
                .doesNotContain("cdn.jsdelivr.net")
                .doesNotContain("unpkg.com");
        assertThat(app)
                .contains("/api/requirement-graphs/builds")
                .contains("/api/requirement-graphs/search")
                .contains("/claims/${encodeURIComponent(claimId)}/${action}")
                .contains("/neighborhood/")
                .contains("/claims/${encodeURIComponent(claimId)}/merge")
                .contains("/claims/${encodeURIComponent(claim.id)}/split")
                .contains("textContent")
                .doesNotContain("innerHTML");
        assertThat(css)
                .contains("prefers-reduced-motion")
                .contains("graph-hero-mark")
                .contains("@media(max-width:620px)");
    }

    private String resource(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
