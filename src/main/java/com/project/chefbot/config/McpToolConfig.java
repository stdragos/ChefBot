package com.project.chefbot.config;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Configuration for MCP tool callbacks.
 * Creates tool callbacks from Spring AI auto-configured MCP clients.
 */
@Configuration
public class McpToolConfig {

    /**
     * Creates tool callbacks from auto-configured MCP clients.
     */
    @Bean(name = "mcpTools")
    @Primary
    public List<ToolCallback> mcpTools(
            @Autowired(required = false) List<McpSyncClient> mcpClients) {

        if (mcpClients == null || mcpClients.isEmpty()) {
            System.out.println("[MCP] No MCP clients available");
            return List.of();
        }

        List<ToolCallback> callbacks = SyncMcpToolCallbackProvider.syncToolCallbacks(mcpClients);

        System.out.println("[MCP] Registered " + callbacks.size() + " tool(s): " +
                callbacks.stream()
                        .map(tc -> tc.getToolDefinition().name())
                        .collect(Collectors.joining(", ")));

        return callbacks;
    }
}
