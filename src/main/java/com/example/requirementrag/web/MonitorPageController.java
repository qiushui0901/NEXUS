package com.example.requirementrag.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 监控页面路由，重定向至静态 monitor.html。
 */
@Controller
public class MonitorPageController {

    /** 平台根路径进入首页，监控工作台保留独立入口。 */
    @GetMapping("/")
    public String homePage() {
        return "redirect:/home.html";
    }

    @GetMapping("/monitor")
    public String monitorPage() {
        return "redirect:/monitor.html";
    }
}
