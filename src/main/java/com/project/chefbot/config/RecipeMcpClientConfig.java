package com.project.chefbot.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Configuration for the remote Recipe MCP Server using streamable HTTP transport.
 */
@Configuration
public class RecipeMcpClientConfig {

    @Value("${recipe.mcp.server.url:http://localhost:8081/mcp}")
    private String recipeMcpServerUrl;

    @Bean(name = "recipeMcpClient")
    public McpSyncClient recipeMcpClient() {
        // Use HttpClientStreamableHttpTransport for streamable HTTP MCP servers
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(recipeMcpServerUrl)
                .build();

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .build();

        // Initialize connection
        try {
            client.initialize();
            System.out.println("[RecipeMCP] Connected to Recipe MCP Server at " + recipeMcpServerUrl);
        } catch (Exception e) {
            System.err.println("[RecipeMCP] Initialization warning (continuing): " + e.getMessage());
        }

        try {
            var tools = client.listTools();
            if (tools != null && tools.tools() != null) {
                System.out.println("[RecipeMCP] Available tools: " +
                    tools.tools().stream()
                        .map(McpSchema.Tool::name)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("none"));
            }
        } catch (Exception e) {
            System.err.println("[RecipeMCP] Failed to list tools: " + e.getMessage());
        }

        return client;
    }

    /**
     * Creates tool callbacks from the recipe MCP client.
     */
    @Bean(name = "recipeToolCallbacks")
    public List<ToolCallback> recipeToolCallbacks(McpSyncClient recipeMcpClient) {
        try {
            List<ToolCallback> callbacks = SyncMcpToolCallbackProvider.syncToolCallbacks(List.of(recipeMcpClient));
            System.out.println("[RecipeMCP] Created " + callbacks.size() + " recipe tool(s): " +
                    callbacks.stream()
                            .map(tc -> tc.getToolDefinition().name())
                            .collect(Collectors.joining(", ")));
            return callbacks;
        } catch (Exception e) {
            System.err.println("[RecipeMCP] Failed to create recipe tools: " + e.getMessage());
            return List.of();
        }
    }
}
