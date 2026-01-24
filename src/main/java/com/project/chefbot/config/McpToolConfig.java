package com.project.chefbot.config;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Configuration for Recipe MCP tool callbacks.
 * The DuckDuckGo and Resend tools are auto-configured by Spring AI (syncMcpToolCallbacks bean).
 */
@Configuration
public class McpToolConfig {

    /**
     * Combine stdio MCP tools and recipe MCP tools into a single primary bean to satisfy Spring's single List<ToolCallback> requirement.
     */
    @Bean(name = "allMcpToolCallbacks")
    @Primary
    public List<ToolCallback> allMcpToolCallbacks(
            @Autowired(required = false) @Qualifier("stdioToolCallbacks") List<ToolCallback> stdioToolCallbacks,
            @Autowired(required = false) @Qualifier("recipeToolCallbacks") List<ToolCallback> recipeToolCallbacks) {

        List<ToolCallback> callbacks = new ArrayList<>();
        if (stdioToolCallbacks != null) {
            callbacks.addAll(stdioToolCallbacks);
        }
        if (recipeToolCallbacks != null) {
            callbacks.addAll(recipeToolCallbacks);
        }

        System.out.println("[MCP] Total registered tools: " + callbacks.size());
        return callbacks;
    }

    /**
     * Creates tool callbacks from our custom recipe MCP client.
     */
    @Bean(name = "recipeToolCallbacks")
    public List<ToolCallback> recipeToolCallbacks(
            @Autowired(required = false) @Qualifier("recipeMcpClient") McpSyncClient recipeMcpClient) {

        if (recipeMcpClient == null) {
            System.out.println("[MCP] Recipe MCP client not available");
            return List.of();
        }

        try {
            List<ToolCallback> callbacks = SyncMcpToolCallbackProvider.syncToolCallbacks(List.of(recipeMcpClient));
            System.out.println("[MCP] Created " + callbacks.size() + " recipe tool(s): " +
                    callbacks.stream()
                            .map(tc -> tc.getToolDefinition().name())
                            .collect(Collectors.joining(", ")));
            return callbacks;
        } catch (Exception e) {
            System.err.println("[MCP] Failed to create recipe tools: " + e.getMessage());
            return List.of();
        }
    }
}
