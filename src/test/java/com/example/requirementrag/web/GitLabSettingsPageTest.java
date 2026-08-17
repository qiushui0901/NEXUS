package com.example.requirementrag.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GitLabSettingsPageTest {

    @Test
    void exposesWorkbenchStatesWithoutLeakingSensitiveFields() throws Exception {
        String html = resource("static/gitlab-settings.html");
        String app = resource("static/assets/gitlab-app.js");
        String api = resource("static/assets/gitlab-api.js");

        assertThat(html)
                .contains("NEXUS · GitLab 管理")
                .contains("/webjars/vue/3.5.13/dist/vue.global.prod.js")
                .contains("正在加载 GitLab 项目")
                .contains("尚无匹配项目")
                .contains("GitLab 项目加载失败")
                .contains("GitLab 项目详情加载失败")
                .contains("@click=\"load\">重试")
                .contains("任务时间线")
                .contains("{{stepMarker(n)}}")
                .doesNotContain("{{n<step")
                .doesNotContain("v-html")
                .doesNotContain("cdn.jsdelivr.net")
                .doesNotContain("unpkg.com");
        assertThat(app)
                .contains("webhookSecret:null")
                .contains("beforeUnmount")
                .contains("clearInterval")
                .contains("document.visibilityState")
                .contains("applyRoute")
                .contains("popstate")
                .contains("openProject(this.selected,false)")
                .contains("stepMarker(n){return n<this.step")
                .doesNotContain("pending-secret")
                .doesNotContain("localStorage.setItem")
                .doesNotContain("innerHTML");
        assertThat(api)
                .contains("/api/integrations/gitlab")
                .contains("/validate-connection")
                .contains("/validate-project")
                .contains("/projects/validate-config")
                .contains("/webhook-secret/rotate")
                .doesNotContain("accessToken=")
                .doesNotContain("webhookSecret=")
                .doesNotContain("localStorage.setItem");
    }

    @Test
    void routesGitLabSubpathsToTheSinglePageApplication() {
        GitLabSettingsPageController controller = new GitLabSettingsPageController();
        assertThat(controller.gitLabSettingsPage()).isEqualTo("forward:/gitlab-settings.html");
    }

    private String resource(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
