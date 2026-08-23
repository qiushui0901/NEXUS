package com.example.requirementrag.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FrontendShellPageTest {

    @Test
    void loadsOneSharedShellAcrossAllCorePages() throws Exception {
        for (String page : List.of(
                "home.html", "knowledge.html", "gitlab-settings.html",
                "wiki.html", "monitor.html")) {
            String html = resource("static/" + page);
            assertThat(html)
                    .as(page)
                    .contains("/assets/design-tokens.css")
                    .contains("/assets/app-shell.css")
                    .contains("/assets/responsive.css")
                    .contains("/assets/error-normalizer.js")
                    .contains("/assets/api-client.js")
                    .contains("/assets/app-shell.js")
                    .contains("data-nexus-shell")
                    .doesNotContain("id=\"apiKey\"")
                    .doesNotContain("placeholder=\"API Key\"");
        }
    }

    @Test
    void keepsCompleteNavigationMobileMenuContextAndConnectionSettingsInOneAsset() throws Exception {
        String shell = resource("static/assets/app-shell.js");

        assertThat(shell)
                .contains("[\"home\", \"总览\", \"/\"]")
                .contains("[\"knowledge\", \"知识库\", \"/knowledge\"]")
                .contains("[\"wiki\", \"Wiki\", \"/wiki\"]")
                .contains("[\"monitor\", \"代码\", \"/monitor\"]")
                .contains("[\"gitlab\", \"GitLab\", \"/settings/gitlab\"]")
                .doesNotContain("[\"versions\", \"版本\", \"/versions\"]")
                .contains("nexus-mobile-drawer")
                .contains("连接设置")
                .contains("pageTitles.versions = \"版本\"")
                .contains("projectId")
                .contains("version")
                .contains("nexus:context-changed")
                .contains("setContext")
                .contains("refreshContext")
                .contains("localStorage.setItem(\"nexus_project_id\"")
                .contains("0.9.4");
        assertThat(shell.indexOf("{value: title, current: true}"))
                .isLessThan(shell.indexOf("{value: liveContext.projectId, current: false}"));
    }

    @Test
    void normalizesHtmlStacksPathsAndCredentialsBeforeDisplay() throws Exception {
        String normalizer = resource("static/assets/error-normalizer.js");

        assertThat(normalizer)
                .contains("<!doctype")
                .contains("stackPattern")
                .contains("\\/Users\\/")
                .contains("authorization")
                .contains("[内部路径]")
                .contains("[已隐藏]");
    }

    @Test
    void deduplicatesRequestHeadersWithoutDependingOnHeaderCase() throws Exception {
        String client = resource("static/assets/api-client.js");

        assertThat(client)
                .contains("key.toLowerCase() === name.toLowerCase()")
                .contains("!hasHeader(\"Content-Type\")")
                .contains("!hasHeader(\"X-API-Key\")");
    }

    private String resource(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
