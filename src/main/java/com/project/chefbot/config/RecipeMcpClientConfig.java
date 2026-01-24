package com.project.chefbot.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

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

        // Initialize connection but swallow SSE/406 noise so the app keeps running
        try {
            client.initialize();
            System.out.println("[RecipeMCP] Connected to Recipe MCP Server at " + recipeMcpServerUrl);
        } catch (Exception e) {
            System.err.println("[RecipeMCP] Initialization warning (continuing): " + e.getMessage());
        }

        // Log available tools
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
}
