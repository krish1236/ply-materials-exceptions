package com.ply.exceptions.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Set;

@Controller
public class BlogController {

    private static final Set<String> SLUGS = Set.of("event-sourced-exceptions");

    @GetMapping("/blog/{slug}")
    public String post(@PathVariable String slug) {
        if (!SLUGS.contains(slug)) {
            return "redirect:/exceptions";
        }
        return "blog/" + slug;
    }
}
