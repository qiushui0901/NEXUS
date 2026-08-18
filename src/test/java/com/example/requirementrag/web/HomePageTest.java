package com.example.requirementrag.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HomePageTest {

    @Test
    void exposesCoreModulesAndFailureTolerantRuntimeStatus() throws Exception {
        String html = new ClassPathResource("static/home.html")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html)
                .contains("运行总览")
                .contains("data-nexus-shell data-page=\"home\"")
                .contains("/assets/design-tokens.css")
                .contains("/assets/app-shell.js")
                .contains("/api/runtime/status")
                .contains("NexusApi.request")
                .contains("可降级")
                .contains("service.message")
                .contains("代码集合")
                .doesNotContain("Ollama")
                .doesNotContain("BGE Reranker")
                .doesNotContain("比较版本")
                .doesNotContain("class=\"modules\"")
                .doesNotContain("class=\"hero\"")
                .doesNotContain("cdn.jsdelivr.net")
                .doesNotContain("unpkg.com");
    }

    @Test
    void keepsHomeAndWorkbenchAsSeparateRoutes() {
        MonitorPageController controller = new MonitorPageController();
        assertThat(controller.homePage()).isEqualTo("redirect:/home.html");
        assertThat(controller.monitorPage()).isEqualTo("redirect:/monitor.html");
    }
}
