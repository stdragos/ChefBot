package com.project.chefbot.tools;

import com.project.chefbot.mcp.McpRecipeServer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * Registers local tools as Spring AI ToolCallbacks.
 * External MCP tools (DuckDuckGo, Resend) are auto-configured by Spring AI MCP Client.
 */
@Configuration
public class McpToolsConfig {

    private final McpRecipeServer recipeServer;

    public McpToolsConfig(McpRecipeServer recipeServer) {
        this.recipeServer = recipeServer;
    }

    /**
     * Register the recipe search tool from the McpRecipeServer.
     * Uses MethodToolCallbackProvider to discover @Tool annotated methods.
     */
    @Bean
    public List<ToolCallback> localToolCallbacks() {
        ToolCallback[] toolArray = MethodToolCallbackProvider.builder()
                .toolObjects(recipeServer)
                .build()
                .getToolCallbacks();

        List<ToolCallback> tools = Arrays.asList(toolArray);

        System.out.println("[Tools] Registered " + tools.size() + " local tool(s): " +
                tools.stream()
                        .map(t -> t.getToolDefinition().name())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("none"));
        return tools;
    }
}

