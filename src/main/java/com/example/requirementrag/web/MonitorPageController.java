package com.example.requirementrag.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 监控页面路由，重定向至静态 monitor.html。
 */
@Controller
public class MonitorPageController {

    /** 根路径与 /monitor 均跳转至监控页。 */
    @GetMapping({"/", "/monitor"})
    public String monitorPage() {
        return "redirect:/monitor.html";
    }
}
