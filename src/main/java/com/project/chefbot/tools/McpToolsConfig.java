package com.project.chefbot.tools;

import com.project.chefbot.mcp.McpConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * Registers MCP tools as Spring AI functions.
 * Reads tool configuration from mcp-config.json.
 */
@Configuration
public class McpToolsConfig {

    private final McpConfig mcpConfig;
    private final RestClient restClient = RestClient.create();

    public McpToolsConfig(McpConfig mcpConfig) {
        this.mcpConfig = mcpConfig;
    }

    public record SearchRequest(String query) {}
    public record SearchResponse(String foundRecipes) {}
    private record McpResponse(boolean success, String result) {}

    @Bean
    @Description("Search for recipes in the database based on ingredients or dish name. The query MUST be in ENGLISH.")
    public Function<SearchRequest, SearchResponse> searchRecipesTool() {
        return request -> {
            String url = mcpConfig.getToolUrl("searchRecipes");
            String encodedQuery = URLEncoder.encode(request.query(), StandardCharsets.UTF_8);
            String fullUrl = url + "?query=" + encodedQuery;
            System.out.println("[MCP Tool] Calling: " + fullUrl);

            try {
                System.out.println("[MCP Tool] Making HTTP GET request...");
                McpResponse response = restClient.get()
                        .uri(fullUrl)
                        .retrieve()
                        .body(McpResponse.class);

                System.out.println("[MCP Tool] Response received: " + response);

                if (response == null) {
                    System.out.println("[MCP Tool] Response was null!");
                    return new SearchResponse("No results found.");
                }

                return new SearchResponse(response.result());
            } catch (Exception e) {
                System.err.println("[MCP Tool] Error: " + e.getClass().getName() + " - " + e.getMessage());
                e.printStackTrace();
                return new SearchResponse("Error calling MCP server: " + e.getMessage());
            }
        };
    }
}

