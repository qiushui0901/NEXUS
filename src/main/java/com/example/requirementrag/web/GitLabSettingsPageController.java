package com.example.requirementrag.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** GitLab 可视化接入与同步管理页面路由。 */
@Controller
@ConditionalOnProperty(prefix = "app.rag.gitlab", name = "ui-enabled",
        havingValue = "true", matchIfMissing = true)
public class GitLabSettingsPageController {

    @GetMapping({"/settings/gitlab", "/settings/gitlab/**"})
    public String gitLabSettingsPage() {
        return "forward:/gitlab-settings.html";
    }
}
