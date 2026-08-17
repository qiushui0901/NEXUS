package com.example.requirementrag.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeManagementPageTest {

    @Test
    void exposesKnowledgeOperationsWithoutExternalFrontendDependencies() throws Exception {
        String html = resource("static/knowledge.html");
        String app = resource("static/assets/knowledge-app.js");
        String api = resource("static/assets/knowledge-api.js");

        assertThat(html)
                .contains("NEXUS · 知识库管理")
                .contains("/webjars/vue/3.5.13/dist/vue.global.prod.js")
                .contains("处理过程")
                .contains("分块")
                .contains("检索测试")
                .contains("没有匹配的知识库")
                .doesNotContain("v-html")
                .doesNotContain("cdn.jsdelivr.net")
                .doesNotContain("unpkg.com");
        assertThat(app)
                .contains("visibilitychange")
                .contains("setTimeout(()=>this.refresh()")
                .contains("selectedChunk")
                .contains("retryDocument")
                .contains("runRetrieval")
                .doesNotContain("innerHTML");
        assertThat(api)
                .contains("/api/knowledge-bases")
                .contains("X-API-Key")
                .doesNotContain("accessToken")
                .doesNotContain("webhookSecret");
    }

    @Test
    void routesKnowledgeSubpathsToTheSinglePageApplication() {
        KnowledgeManagementPageController controller = new KnowledgeManagementPageController();
        assertThat(controller.knowledgePage()).isEqualTo("forward:/knowledge.html");
    }

    private String resource(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
