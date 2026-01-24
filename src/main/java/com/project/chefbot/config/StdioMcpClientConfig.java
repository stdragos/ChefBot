package com.project.chefbot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
public class StdioMcpClientConfig {

    @Value("${spring.ai.mcp.client.request-timeout:30s}")
    private String requestTimeout;

    @Value("${spring.ai.mcp.client.stdio.connections.duckduckgo.command:docker}")
    private String ddgCommand;

    @Value("${spring.ai.mcp.client.stdio.connections.duckduckgo.args:run,-i,--rm,mcp/duckduckgo}")
    private String ddgArgs;

    @Value("${spring.ai.mcp.client.stdio.connections.resend.command:docker}")
    private String resendCommand;

    @Value("${spring.ai.mcp.client.stdio.connections.resend.args:run,-i,--rm,mcp/resend}")
    private String resendArgs;

    private Duration parseTimeout(String value) {
        try {
            if (value.matches("\\d+[sSmMhH]")) {
                // Handle simple formats like 30s
                char unit = Character.toLowerCase(value.charAt(value.length() - 1));
                long amount = Long.parseLong(value.substring(0, value.length() - 1));
                return switch (unit) {
                    case 's' -> Duration.ofSeconds(amount);
                    case 'm' -> Duration.ofMinutes(amount);
                    case 'h' -> Duration.ofHours(amount);
                    default -> Duration.ofSeconds(30);
                };
            }
            return Duration.parse(value);
        } catch (Exception e) {
            return Duration.ofSeconds(30);
        }
    }

    private List<String> splitArgs(String args) {
        return Arrays.stream(args.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @Bean
    public McpSyncClient duckduckgoMcpClient() {
        ServerParameters params = ServerParameters.builder(ddgCommand)
                .args(splitArgs(ddgArgs))
                .build();
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(new ObjectMapper());
        StdioClientTransport transport = new StdioClientTransport(params, mapper);

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(parseTimeout(requestTimeout))
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .build();

        try {
            client.initialize();
            System.out.println("[MCP-DDG] Connected and ready");
        } catch (Exception e) {
            System.err.println("[MCP-DDG] Initialization failed: " + e.getMessage());
        }
        return client;
    }

    @Bean
    public McpSyncClient resendMcpClient() {
        ServerParameters params = ServerParameters.builder(resendCommand)
                .args(splitArgs(resendArgs))
                .build();
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(new ObjectMapper());
        StdioClientTransport transport = new StdioClientTransport(params, mapper);

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(parseTimeout(requestTimeout))
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .build();

        try {
            client.initialize();
            System.out.println("[MCP-Resend] Connected and ready");
        } catch (Exception e) {
            System.err.println("[MCP-Resend] Initialization failed: " + e.getMessage());
        }
        return client;
    }

    @Bean(name = "stdioToolCallbacks")
    public List<ToolCallback> stdioToolCallbacks(McpSyncClient duckduckgoMcpClient,
                                                 McpSyncClient resendMcpClient) {
        List<ToolCallback> callbacks = SyncMcpToolCallbackProvider.syncToolCallbacks(List.of(duckduckgoMcpClient, resendMcpClient));
        System.out.println("[MCP-STDIO] Registered " + callbacks.size() + " stdio tool(s): " +
                callbacks.stream().map(tc -> tc.getToolDefinition().name()).collect(Collectors.joining(", ")));
        return callbacks;
    }
}
