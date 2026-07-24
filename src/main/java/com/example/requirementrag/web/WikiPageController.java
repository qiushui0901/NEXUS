package com.example.requirementrag.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Human-readable Wiki workspace route. */
@Controller
public class WikiPageController {
    @GetMapping("/wiki")
    public String wikiPage() {
        return "redirect:/wiki.html";
    }
}
