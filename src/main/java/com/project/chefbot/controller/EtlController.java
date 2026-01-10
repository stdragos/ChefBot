package com.project.chefbot.controller;

import com.project.chefbot.etl.KnowledgeBaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/etl")
public class EtlController {

    private final KnowledgeBaseService knowledgeBaseService;

    public EtlController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @PostMapping("/scrape")
    public ResponseEntity<String> startScraping(@RequestBody List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return ResponseEntity.badRequest().body("URL list is empty.");
        }

        knowledgeBaseService.processUrlsAsync(urls);

        return ResponseEntity.ok("Process started in background for " + urls.size() + " URLs. Check console for progress.");
    }
}