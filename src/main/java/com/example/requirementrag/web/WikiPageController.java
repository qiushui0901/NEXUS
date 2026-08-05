package com.example.requirementrag.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Wiki 工作台页面路由。 */
@Controller
public class WikiPageController {
    /** Wiki 工作台页面，重定向至静态 wiki.html。对应 GET /wiki。 */
    @GetMapping("/wiki")
    public String wikiPage() {
        return "redirect:/wiki.html";
    }
}
