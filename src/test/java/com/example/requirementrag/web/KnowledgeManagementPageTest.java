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
        String statusContract = resource("static/assets/status-contract.js");

        assertThat(html)
                .contains("NEXUS · 知识库管理")
                .contains("/webjars/vue/3.5.13/dist/vue.global.prod.js")
                .contains("处理过程")
                .contains("分块")
                .contains("检索测试")
                .contains("返回知识库")
                .contains("当前项目已发布代码索引")
                .contains("hit.commitSha")
                .contains("本次检索指标")
                .contains("阶段明细")
                .contains("没有匹配的知识库")
                .contains("data-nexus-shell data-page=\"knowledge\"")
                .contains("nx-mobile-records")
                .doesNotContain("v-html")
                .doesNotContain("cdn.jsdelivr.net")
                .doesNotContain("unpkg.com");
        assertThat(app)
                .contains("visibilitychange")
                .contains("setTimeout(()=>this.refresh()")
                .contains("selectedChunk")
                .contains("retryDocument")
                .contains("runRetrieval")
                .contains("backToBase")
                .contains("retrievalSourceCount")
                .contains("retrievalDiagnostics")
                .contains("performance.now()")
                .contains("NexusShell.setContext")
                .doesNotContain("innerHTML");
        assertThat(api)
                .contains("/api/knowledge-bases")
                .contains("NexusApi.request")
                .doesNotContain("accessToken")
                .doesNotContain("webhookSecret");
        assertThat(statusContract).contains("NO_RESULTS: \"无结果\"");
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
