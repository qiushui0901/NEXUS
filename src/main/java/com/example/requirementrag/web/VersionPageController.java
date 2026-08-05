package com.example.requirementrag.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 版本对比工作台页面路由。 */
@Controller
public class VersionPageController {
    /** 版本对比工作台页面，重定向至静态 versions.html。对应 GET /versions。 */
    @GetMapping("/versions")
    public String versionsPage() {
        return "redirect:/versions.html";
    }
}
