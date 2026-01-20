package com.project.chefbot.mcp;

import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Recipe search service using Spring AI's @Tool annotation.
 * Provides recipe search functionality directly as an AI tool.
 */
@Service
public class McpRecipeServer {

    private final VectorStore vectorStore;

    public McpRecipeServer(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Search for recipes in the local database.
     * This tool is automatically registered with Spring AI.
     */
    @Tool(description = "Search for recipes in the local database based on ingredients or dish name. Query MUST be in ENGLISH. Returns recipe text if found, or a message if no recipes match.")
    public String searchRecipes(
            @ToolParam(description = "The search query - ingredient names, dish name, or cuisine type (e.g., 'chicken pasta', 'vegan soup', 'italian')")
            String query) {

        System.out.println("[Recipe Search] Query: " + query);

        if (query == null || query.trim().isEmpty()) {
            return "Error: Search query cannot be empty. Please provide ingredients or a dish name.";
        }

        try {
            FilterExpressionBuilder filter = new FilterExpressionBuilder();

            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query.trim())
                    .topK(5)
                    .similarityThreshold(0.45)
                    .filterExpression(filter.eq("type", "web-recipe").build())
                    .build();

            List<Document> docs = vectorStore.similaritySearch(searchRequest);

            if (docs == null || docs.isEmpty()) {
                System.out.println("[Recipe Search] No results for: " + query);
                return "No recipes found in local database for: " + query + ". Try searching the web instead.";
            }

            String result = docs.stream()
                    .map(Document::getText)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n\n---\n\n"));

            System.out.println("[Recipe Search] Found " + docs.size() + " recipe(s)");
            return result;

        } catch (Exception e) {
            System.err.println("[Recipe Search] Error: " + e.getMessage());
            return "Error searching recipes: " + e.getMessage();
        }
    }
}

