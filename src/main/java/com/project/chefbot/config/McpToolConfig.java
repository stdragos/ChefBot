package com.project.chefbot.config;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for aggregating MCP tool callbacks from different sources.
 * Individual tool callbacks are created in their respective configuration classes:
 * - stdioToolCallbacks from StdioMcpClientConfig
 * - recipeToolCallbacks from RecipeMcpClientConfig
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
}
