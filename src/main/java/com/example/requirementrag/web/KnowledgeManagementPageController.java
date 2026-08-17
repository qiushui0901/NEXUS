package com.example.requirementrag.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 知识管理工作台页面路由。 */
@Controller
@ConditionalOnProperty(prefix = "app.knowledge-management", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class KnowledgeManagementPageController {

    /** 知识管理页面及其前端子路由统一交给静态 Vue 应用。 */
    @GetMapping({"/knowledge", "/knowledge/**"})
    public String knowledgePage() {
        return "forward:/knowledge.html";
    }
}
