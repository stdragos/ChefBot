package com.project.chefbot.mcp;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Simple MCP Server exposed as a REST endpoint.
 * Provides recipe search functionality via HTTP API.
 */
@RestController
@RequestMapping("/mcp")
public class McpRecipeServer {

    private final VectorStore vectorStore;

    public McpRecipeServer(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Search for recipes by query.
     * GET /mcp/tools/searchRecipes?query=pasta
     */
    @GetMapping("/tools/searchRecipes")
    public SearchResponse searchRecipes(@RequestParam String query) {
        System.out.println("[MCP Server] Searching for: " + query);

        if (query == null || query.trim().isEmpty()) {
            return new SearchResponse(false, "Error: Query cannot be empty");
        }

        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();

            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(5)
                    .similarityThreshold(0.45)
                    .filterExpression(b.eq("type", "web-recipe").build())
                    .build();

            List<Document> docs = vectorStore.similaritySearch(searchRequest);

            if (docs == null || docs.isEmpty()) {
                System.out.println("[MCP Server] No documents found for: " + query);
                return new SearchResponse(true, "No relevant recipes found in the database.");
            }

            String result = docs.stream()
                    .map(Document::getText)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n\n---\n\n"));

            System.out.println("[MCP Server] Found " + docs.size() + " recipes");
            return new SearchResponse(true, result);

        } catch (Exception e) {
            System.err.println("[MCP Server] Search error: " + e.getMessage());
            return new SearchResponse(false, "Error searching recipes: " + e.getMessage());
        }
    }

    /**
     * List available tools.
     * GET /mcp/tools
     */
    @GetMapping("/tools")
    public ToolsResponse listTools() {
        return new ToolsResponse(List.of(
                new ToolInfo(
                        "searchRecipes",
                        "Search for recipes in the database based on ingredients or dish name. The query MUST be in ENGLISH.",
                        "/mcp/tools/searchRecipes?query={query}"
                )
        ));
    }

    /**
     * Debug endpoint to list all documents in vector store.
     * GET /mcp/debug
     */
    @GetMapping("/debug")
    public SearchResponse debugVectorStore() {
        try {
            SearchRequest request = SearchRequest.builder()
                    .query("recipe food")
                    .topK(10)
                    .similarityThreshold(0.0)
                    .build();

            List<Document> docs = vectorStore.similaritySearch(request);

            if (docs == null || docs.isEmpty()) {
                return new SearchResponse(false, "Vector store is empty or no documents found.");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(docs.size()).append(" documents:\n\n");
            for (int i = 0; i < docs.size(); i++) {
                Document doc = docs.get(i);
                sb.append("--- Document ").append(i + 1).append(" ---\n");
                sb.append("ID: ").append(doc.getId()).append("\n");
                sb.append("Metadata: ").append(doc.getMetadata()).append("\n");
                sb.append("Content preview: ").append(
                        doc.getText() != null ? doc.getText().substring(0, Math.min(200, doc.getText().length())) + "..." : "null"
                ).append("\n\n");
            }

            return new SearchResponse(true, sb.toString());
        } catch (Exception e) {
            return new SearchResponse(false, "Debug error: " + e.getMessage());
        }
    }

    /**
     * Health check endpoint.
     * GET /mcp/health
     */
    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("ok", "chefbot-recipe-server", "1.0.0");
    }

    // Response DTOs
    public record SearchResponse(boolean success, String result) {}
    public record ToolInfo(String name, String description, String endpoint) {}
    public record ToolsResponse(List<ToolInfo> tools) {}
    public record HealthResponse(String status, String name, String version) {}
}

