package com.project.chefbot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads and provides access to MCP configuration from mcp-config.json.
 */
@Component
public class McpConfig {

    private JsonNode config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() throws IOException {
        ClassPathResource resource = new ClassPathResource("mcp-config.json");
        try (InputStream is = resource.getInputStream()) {
            config = objectMapper.readTree(is);
        }
        System.out.println("[MCP Config] Loaded configuration from mcp-config.json");
    }

    /**
     * Gets the base URL of the MCP server.
     */
    public String getBaseUrl() {
        return config.path("mcpServer").path("baseUrl").asText("http://localhost:8080");
    }

    /**
     * Gets the full URL for a specific tool.
     */
    public String getToolUrl(String toolName) {
        String endpoint = config.path("tools").path(toolName).path("endpoint").asText();
        return getBaseUrl() + endpoint;
    }

    /**
     * Gets the HTTP method for a specific tool.
     */
    public String getToolMethod(String toolName) {
        return config.path("tools").path(toolName).path("method").asText("GET");
    }

    /**
     * Gets the description of a specific tool.
     */
    public String getToolDescription(String toolName) {
        return config.path("tools").path(toolName).path("description").asText("");
    }

    /**
     * Gets the server name.
     */
    public String getServerName() {
        return config.path("mcpServer").path("name").asText("mcp-server");
    }

    /**
     * Gets the server version.
     */
    public String getServerVersion() {
        return config.path("mcpServer").path("version").asText("1.0.0");
    }
}

