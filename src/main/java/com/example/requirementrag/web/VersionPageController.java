package com.example.requirementrag.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Human-readable version comparison workspace route. */
@Controller
public class VersionPageController {
    @GetMapping("/versions")
    public String versionsPage() {
        return "redirect:/versions.html";
    }
}
