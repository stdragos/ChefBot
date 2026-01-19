package com.project.chefbot.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.chefbot.mcp.McpConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Registers MCP tools as Spring AI functions.
 * Reads tool configuration from mcp-config.json.
 * Includes DuckDuckGo MCP server tools for web search fallback.
 */
@Configuration
public class McpToolsConfig {

    private final McpConfig mcpConfig;
    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${resend.api.key}")
    private String resendApiKey;

    @Value("${resend.sender.email}")
    private String senderEmail;

    @Value("${resend.reply.to.email:}")
    private String replyToEmail;

    public McpToolsConfig(McpConfig mcpConfig) {
        this.mcpConfig = mcpConfig;
    }

    // Records for database search
    public record SearchRequest(String query) {}
    public record SearchResponse(String foundRecipes) {}
    private record McpResponse(boolean success, String result) {}

    // Records for DuckDuckGo web search
    public record WebSearchRequest(String query, Integer maxResults) {}
    public record WebSearchResponse(String results) {}

    // Records for DuckDuckGo fetch content
    public record FetchContentRequest(String url) {}
    public record FetchContentResponse(String content) {}

    // Records for Resend email
    public record SendEmailRequest(String to, String subject, String text) {}
    public record SendEmailResponse(String result) {}

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

    /**
     * Web search tool using DuckDuckGo MCP server in Docker.
     * Use this as a fallback when database search returns no results.
     * After getting search results, extract a URL and use fetchWebContentTool to get the full recipe.
     */
    @Bean
    @Description("Search the web using DuckDuckGo for recipes when database returns no results. Returns search results with URLs. You MUST then call fetchWebContentTool with one of the URLs to get the full recipe content.")
    public Function<WebSearchRequest, WebSearchResponse> webSearchTool() {
        return request -> {
            System.out.println("[DuckDuckGo MCP] Starting web search for: " + request.query());

            try {
                int maxResults = request.maxResults() != null ? request.maxResults() : 5;

                // Start Docker container with MCP server
                ProcessBuilder pb = new ProcessBuilder(
                    "docker", "run", "-i", "--rm", "mcp/duckduckgo"
                );
                pb.redirectErrorStream(false);
                Process process = pb.start();

                BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

                // Step 1: Send initialize request
                Map<String, Object> initRequest = Map.of(
                    "jsonrpc", "2.0",
                    "id", 1,
                    "method", "initialize",
                    "params", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "chefbot", "version", "1.0.0")
                    )
                );
                String initJson = objectMapper.writeValueAsString(initRequest);
                System.out.println("[DuckDuckGo MCP] Sending init: " + initJson);
                writer.write(initJson);
                writer.newLine();
                writer.flush();

                // Read init response
                String initResponse = reader.readLine();
                System.out.println("[DuckDuckGo MCP] Init response: " + initResponse);

                // Step 2: Send initialized notification
                Map<String, Object> initializedNotification = Map.of(
                    "jsonrpc", "2.0",
                    "method", "notifications/initialized"
                );
                writer.write(objectMapper.writeValueAsString(initializedNotification));
                writer.newLine();
                writer.flush();

                // Step 3: Call the search tool
                Map<String, Object> toolRequest = Map.of(
                    "jsonrpc", "2.0",
                    "id", 2,
                    "method", "tools/call",
                    "params", Map.of(
                        "name", "search",
                        "arguments", Map.of(
                            "query", request.query(),
                            "max_results", maxResults
                        )
                    )
                );
                String toolJson = objectMapper.writeValueAsString(toolRequest);
                System.out.println("[DuckDuckGo MCP] Sending tool request: " + toolJson);
                writer.write(toolJson);
                writer.newLine();
                writer.flush();

                // DON'T close the output stream yet - the MCP server needs to send responses
                // Just flush to ensure the request is sent

                // Read responses - MCP sends notifications before the actual result
                // We need to find the response with "id":2 which is our tool call result
                String toolResponse = null;
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    String preview = line.length() > 200 ? line.substring(0, 200) + "..." : line;
                    System.out.println("[DuckDuckGo MCP] Line " + lineCount + ": " + preview);

                    // Skip notifications (they have "method" field but no "id")
                    // We want the response with "id":2
                    if (line.contains("\"id\":2")) {
                        toolResponse = line;
                        System.out.println("[DuckDuckGo MCP] Found tool response!");
                        break;
                    }
                }
                System.out.println("[DuckDuckGo MCP] Total lines read: " + lineCount);
                System.out.println("[DuckDuckGo MCP] Tool response found: " + (toolResponse != null));

                // Now close streams and cleanup
                writer.close();
                reader.close();

                // Wait for process to complete
                boolean finished = process.waitFor(30, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return new WebSearchResponse("Web search timed out.");
                }

                if (toolResponse == null || toolResponse.isEmpty()) {
                    return new WebSearchResponse("No response from web search.");
                }

                // Parse JSON-RPC response
                JsonNode jsonResponse = objectMapper.readTree(toolResponse);
                if (jsonResponse.has("result")) {
                    JsonNode result = jsonResponse.get("result");
                    if (result.has("content") && result.get("content").isArray()) {
                        StringBuilder searchResults = new StringBuilder();
                        for (JsonNode content : result.get("content")) {
                            if (content.has("text")) {
                                searchResults.append(content.get("text").asText()).append("\n");
                            }
                        }
                        String results = searchResults.toString().trim();
                        System.out.println("[DuckDuckGo MCP] Parsed results: " + results);
                        return new WebSearchResponse(results.isEmpty() ? "No web results found." : results);
                    }
                }

                if (jsonResponse.has("error")) {
                    return new WebSearchResponse("Web search error: " + jsonResponse.get("error").toString());
                }

                return new WebSearchResponse("No web results found.");

            } catch (Exception e) {
                System.err.println("[DuckDuckGo MCP] Error: " + e.getClass().getName() + " - " + e.getMessage());
                e.printStackTrace();
                return new WebSearchResponse("Web search failed: " + e.getMessage());
            }
        };
    }

    /**
     * Fetch content from a webpage using DuckDuckGo MCP server.
     * REQUIRED: Call this after webSearchTool to get the full recipe details from a URL.
     */
    @Bean
    @Description("Fetch full recipe content from a URL. You MUST call this after webSearchTool to get the complete recipe details from the webpage. Provide the URL from the search results.")
    public Function<FetchContentRequest, FetchContentResponse> fetchWebContentTool() {
        return request -> {
            System.out.println("[DuckDuckGo MCP] Fetching content from: " + request.url());

            try {
                // Start Docker container with MCP server
                ProcessBuilder pb = new ProcessBuilder(
                    "docker", "run", "-i", "--rm", "mcp/duckduckgo"
                );
                pb.redirectErrorStream(false);
                Process process = pb.start();

                BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

                // Step 1: Send initialize request
                Map<String, Object> initRequest = Map.of(
                    "jsonrpc", "2.0",
                    "id", 1,
                    "method", "initialize",
                    "params", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "chefbot", "version", "1.0.0")
                    )
                );
                writer.write(objectMapper.writeValueAsString(initRequest));
                writer.newLine();
                writer.flush();

                // Read init response
                String initResponse = reader.readLine();
                System.out.println("[DuckDuckGo MCP] Init response: " + initResponse);

                // Step 2: Send initialized notification
                Map<String, Object> initializedNotification = Map.of(
                    "jsonrpc", "2.0",
                    "method", "notifications/initialized"
                );
                writer.write(objectMapper.writeValueAsString(initializedNotification));
                writer.newLine();
                writer.flush();

                // Step 3: Call the fetch_content tool
                Map<String, Object> toolRequest = Map.of(
                    "jsonrpc", "2.0",
                    "id", 2,
                    "method", "tools/call",
                    "params", Map.of(
                        "name", "fetch_content",
                        "arguments", Map.of(
                            "url", request.url()
                        )
                    )
                );
                String toolJson = objectMapper.writeValueAsString(toolRequest);
                System.out.println("[DuckDuckGo MCP] Sending fetch request: " + toolJson);
                writer.write(toolJson);
                writer.newLine();
                writer.flush();

                // DON'T close the output stream yet - the MCP server needs to send responses

                // Read responses - MCP sends notifications before the actual result
                // We need to find the response with "id":2 which is our tool call result
                String toolResponse = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[DuckDuckGo MCP] Received line: " + line.substring(0, Math.min(200, line.length())));

                    // Skip notifications (they have "method" field but no "id")
                    // We want the response with "id":2
                    if (line.contains("\"id\":2")) {
                        toolResponse = line;
                        break;
                    }
                }
                System.out.println("[DuckDuckGo MCP] Fetch response: " + (toolResponse != null ? toolResponse.substring(0, Math.min(300, toolResponse.length())) : "null"));

                // Now close streams and cleanup
                writer.close();
                reader.close();

                // Wait for process to complete
                boolean finished = process.waitFor(30, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return new FetchContentResponse("Content fetch timed out.");
                }

                if (toolResponse == null || toolResponse.isEmpty()) {
                    return new FetchContentResponse("No response from fetch.");
                }

                // Parse JSON-RPC response
                JsonNode jsonResponse = objectMapper.readTree(toolResponse);
                if (jsonResponse.has("result")) {
                    JsonNode result = jsonResponse.get("result");
                    if (result.has("content") && result.get("content").isArray()) {
                        StringBuilder contentBuilder = new StringBuilder();
                        for (JsonNode content : result.get("content")) {
                            if (content.has("text")) {
                                contentBuilder.append(content.get("text").asText()).append("\n");
                            }
                        }
                        String content = contentBuilder.toString().trim();
                        // Limit content length to avoid overwhelming the LLM
                        if (content.length() > 5000) {
                            content = content.substring(0, 5000) + "... [content truncated]";
                        }
                        System.out.println("[DuckDuckGo MCP] Fetched content length: " + content.length());
                        return new FetchContentResponse(content.isEmpty() ? "Could not extract content from URL." : content);
                    }
                }

                if (jsonResponse.has("error")) {
                    return new FetchContentResponse("Fetch error: " + jsonResponse.get("error").toString());
                }

                return new FetchContentResponse("Could not fetch content from URL.");

            } catch (Exception e) {
                System.err.println("[DuckDuckGo MCP] Fetch error: " + e.getClass().getName() + " - " + e.getMessage());
                e.printStackTrace();
                return new FetchContentResponse("Content fetch failed: " + e.getMessage());
            }
        };
    }

    /**
     * Send an email using Resend MCP server.
     * Use this to send recipes to the user when they request it.
     */
    @Bean
    @Description("Send an email with recipe content to the user's email address. Use this when the user asks you to email them a recipe. Parameters: to (recipient email), subject (email subject), text (plain text content with the recipe).")
    public Function<SendEmailRequest, SendEmailResponse> sendEmailTool() {
        return request -> {
            System.out.println("[Resend MCP] Sending email to: " + request.to());
            System.out.println("[Resend MCP] Subject: " + request.subject());

            try {
                // Start Docker container with Resend MCP server
                ProcessBuilder pb = new ProcessBuilder(
                    "docker", "run", "-i", "--rm",
                    "-e", "RESEND_API_KEY=" + resendApiKey,
                    "-e", "SENDER_EMAIL_ADDRESS=" + senderEmail,
                    "-e", "REPLY_TO_EMAIL_ADDRESSES=" + replyToEmail,
                    "mcp/resend"
                );
                pb.redirectErrorStream(false);
                Process process = pb.start();

                BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

                // Step 1: Send initialize request
                Map<String, Object> initRequest = Map.of(
                    "jsonrpc", "2.0",
                    "id", 1,
                    "method", "initialize",
                    "params", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "chefbot", "version", "1.0.0")
                    )
                );
                writer.write(objectMapper.writeValueAsString(initRequest));
                writer.newLine();
                writer.flush();

                // Read init response
                String initResponse = reader.readLine();
                System.out.println("[Resend MCP] Init response: " + initResponse);

                // Step 2: Send initialized notification
                Map<String, Object> initializedNotification = Map.of(
                    "jsonrpc", "2.0",
                    "method", "notifications/initialized"
                );
                writer.write(objectMapper.writeValueAsString(initializedNotification));
                writer.newLine();
                writer.flush();

                // Step 3: Call the send-email tool
                Map<String, Object> toolRequest = Map.of(
                    "jsonrpc", "2.0",
                    "id", 2,
                    "method", "tools/call",
                    "params", Map.of(
                        "name", "send-email",
                        "arguments", Map.of(
                            "to", request.to(),
                            "subject", request.subject(),
                            "text", request.text()
                        )
                    )
                );
                String toolJson = objectMapper.writeValueAsString(toolRequest);
                System.out.println("[Resend MCP] Sending email request: " + toolJson);
                writer.write(toolJson);
                writer.newLine();
                writer.flush();

                // Read responses - find the response with "id":2
                String toolResponse = null;
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    System.out.println("[Resend MCP] Line " + lineCount + ": " + line);

                    if (line.contains("\"id\":2")) {
                        toolResponse = line;
                        System.out.println("[Resend MCP] Found email response!");
                        break;
                    }
                }

                // Close streams and cleanup
                writer.close();
                reader.close();

                // Wait for process to complete
                boolean finished = process.waitFor(30, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return new SendEmailResponse("Email sending timed out.");
                }

                if (toolResponse == null || toolResponse.isEmpty()) {
                    return new SendEmailResponse("No response from email service.");
                }

                // Parse JSON-RPC response
                JsonNode jsonResponse = objectMapper.readTree(toolResponse);
                if (jsonResponse.has("result")) {
                    JsonNode result = jsonResponse.get("result");
                    if (result.has("content") && result.get("content").isArray()) {
                        StringBuilder resultBuilder = new StringBuilder();
                        for (JsonNode content : result.get("content")) {
                            if (content.has("text")) {
                                resultBuilder.append(content.get("text").asText()).append("\n");
                            }
                        }
                        String resultText = resultBuilder.toString().trim();
                        System.out.println("[Resend MCP] Email sent successfully: " + resultText);
                        return new SendEmailResponse(resultText.isEmpty() ? "Email sent successfully!" : resultText);
                    }
                }

                if (jsonResponse.has("error")) {
                    String errorMsg = "Email sending error: " + jsonResponse.get("error").toString();
                    System.err.println("[Resend MCP] " + errorMsg);
                    return new SendEmailResponse(errorMsg);
                }

                return new SendEmailResponse("Email sent, but no confirmation received.");

            } catch (Exception e) {
                System.err.println("[Resend MCP] Error: " + e.getClass().getName() + " - " + e.getMessage());
                e.printStackTrace();
                return new SendEmailResponse("Email sending failed: " + e.getMessage());
            }
        };
    }
}

