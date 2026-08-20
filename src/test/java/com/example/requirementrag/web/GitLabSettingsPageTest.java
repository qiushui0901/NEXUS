package com.example.requirementrag.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GitLabSettingsPageTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GitLabSettingsPageController.class);

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
                .contains("关联 GitLab 账号")
                .contains("GitLab 账号")
                .contains("搜索账号项目")
                .contains("导入到业务项目")
                .contains("本批仓库统一归属同一个既有业务项目")
                .contains("下一步：配置导入")
                .contains("确认项目配置")
                .contains("确认导入并开始同步")
                .contains("返回选择项目")
                .contains("返回项目列表")
                .contains("返回 GitLab 账号")
                .contains("重新启用")
                .contains("正在读取远端分支")
                .contains("代码 collection")
                .contains("Webhook Secret 仅在当前页面显示")
                .contains("任务时间线")
                .contains("data-nexus-shell data-page=\"gitlab\"")
                .contains("nx-mobile-records")
                .doesNotContain("v-html")
                .doesNotContain("需求 collection")
                .doesNotContain("cdn.jsdelivr.net")
                .doesNotContain("unpkg.com");
        assertThat(app)
                .contains("beforeUnmount")
                .contains("clearInterval")
                .contains("document.visibilityState")
                .contains("applyRoute")
                .contains("popstate")
                .contains("openProject(this.selected,false,true)")
                .contains("if(this.polling||this.loading||this.busy)return")
                .contains("loadRemoteProjects(this.remotePage.page,true)")
                .contains("loadProjects(true)")
                .contains("if(!silent){this.loading=true;this.error=null;}")
                .contains("if(!silent)this.loading=false")
                .contains("versionState(project)")
                .contains("navigator.clipboard.writeText")
                .contains("emptyConnectionForm")
                .contains("resetSensitive()")
                .contains("this.connectionForm=emptyConnectionForm()")
                .contains("this.reauthorizeToken=\"\"")
                .contains("this.importResults=null")
                .contains("selectedRemote")
                .contains("importConfigs")
                .contains("importStep:\"select\"")
                .contains("branchOptions")
                .contains("branchLabel")
                .contains("GitLabApi.remoteBranches")
                .contains("GitLabApi.enable")
                .contains("openImportConfig")
                .contains("backToProjectSelection")
                .contains("if(this.importStep!==\"configure\"||!this.importConfigsValid)return")
                .contains("GitLabApi.importProjects")
                .contains("targetBusinessProjectId")
                .doesNotContain("if(this.view===\"detail\")return this.openProject(this.selected,false)")
                .doesNotContain("pending-secret")
                .doesNotContain("localStorage.setItem")
                .doesNotContain("innerHTML");
        assertThat(app.indexOf("openImportConfig"))
                .isLessThan(app.indexOf("GitLabApi.importProjects"));
        assertThat(api)
                .contains("/api/integrations/gitlab")
                .contains("/validate-connection")
                .contains("/validate-project")
                .contains("/projects/validate-config")
                .contains("/webhook-secret/rotate")
                .contains("/connections")
                .contains("/reauthorize")
                .contains("/branches")
                .contains("/enable")
                .contains("/imports")
                .contains("businessProjectId")
                .contains("NexusApi.request")
                .doesNotContain("accessToken=")
                .doesNotContain("webhookSecret=")
                .doesNotContain("localStorage.setItem");
    }

    @Test
    void routesGitLabSubpathsToTheSinglePageApplication() {
        GitLabSettingsPageController controller = new GitLabSettingsPageController();
        assertThat(controller.gitLabSettingsPage()).isEqualTo("forward:/gitlab-settings.html");
    }

    @Test
    void keepsPageRouteAvailableWhenIntegrationBackendIsDisabled() {
        contextRunner
                .withPropertyValues(
                        "app.rag.gitlab.enabled=false",
                        "app.rag.gitlab.ui-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(GitLabSettingsPageController.class));
    }

    @Test
    void hidesPageRouteWhenUiIsExplicitlyDisabled() {
        contextRunner
                .withPropertyValues(
                        "app.rag.gitlab.enabled=true",
                        "app.rag.gitlab.ui-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(GitLabSettingsPageController.class));
    }

    private String resource(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
