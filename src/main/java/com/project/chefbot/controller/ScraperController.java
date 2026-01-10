package com.project.chefbot.controller;

import com.project.chefbot.etl.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/scraper")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ScraperController {

    private final KnowledgeBaseService kbService;

    @GetMapping
    public String showScraperPage(Model model) {
        model.addAttribute("recipes", kbService.getAllRecipes());
        return "scraper";
    }

    @PostMapping("/add")
    public String addUrls(@RequestParam("urls") String urlsText) {
        if (urlsText != null && !urlsText.isBlank()) {
            List<String> urls = Arrays.stream(urlsText.split("\\r?\\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            kbService.processUrlsAsync(urls);
        }
        return "redirect:/scraper?started";
    }

    @PostMapping("/delete/{id}")
    public String deleteRecipe(@PathVariable Long id) {
        kbService.deleteRecipe(id);
        return "redirect:/scraper";
    }
}