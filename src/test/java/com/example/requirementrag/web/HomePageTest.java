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
                .contains("版本化需求、代码和测试知识平台")
                .contains("href=\"/knowledge\"")
                .contains("href=\"/wiki\"")
                .contains("href=\"/versions\"")
                .contains("href=\"/monitor\"")
                .contains("/api/runtime/status")
                .contains("const esc=")
                .contains("X-API-Key")
                .contains("可降级")
                .contains("代码集合")
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
