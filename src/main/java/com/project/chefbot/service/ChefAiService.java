package com.project.chefbot.service;

import com.project.chefbot.dto.CreateSessionRequest;
import com.project.chefbot.model.ChatMessage;
import com.project.chefbot.model.CookingSession;
import com.project.chefbot.model.User;
import com.project.chefbot.repository.ChatMessageRepository;
import com.project.chefbot.repository.CookingSessionRepository;
import com.project.chefbot.repository.UserRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChefAiService {

    private final ChatModel chatModel;
    private final CookingSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final ConversationVectorService vectorService;
    private final UserRepository userRepo;
    private final List<ToolCallback> toolCallbacks;

    public ChefAiService(ChatModel chatModel,
                         CookingSessionRepository sessionRepo,
                         ChatMessageRepository messageRepo,
                         @Autowired(required = false) ConversationVectorService vectorService,
                         UserRepository userRepo,
                         @Autowired(required = false) @Qualifier("mcpTools") List<ToolCallback> mcpTools,
                         @Autowired(required = false) @Qualifier("localToolCallbacks") List<ToolCallback> localTools) {
        this.chatModel = chatModel;
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.vectorService = vectorService;
        this.userRepo = userRepo;

        // Combine MCP tools and local tools
        List<ToolCallback> allTools = new ArrayList<>();
        if (mcpTools != null) allTools.addAll(mcpTools);
        if (localTools != null) allTools.addAll(localTools);
        this.toolCallbacks = allTools;

        // Log available tools at startup
        if (this.toolCallbacks.isEmpty()) {
            System.out.println("[ChefAiService] No tools available");
        } else {
            System.out.println("[ChefAiService] Available tools (" + this.toolCallbacks.size() + "): " +
                this.toolCallbacks.stream()
                    .map(tc -> tc.getToolDefinition().name())
                    .collect(Collectors.joining(", ")));
        }
    }

    public Long createSession(CreateSessionRequest request) {
        CookingSession session = new CookingSession();
        session.setSessionName(request.getSessionName());
        session.setChefPersonality(request.getChefPersonality());
        session.setDietType((request.getDietType() == null || request.getDietType().isEmpty()) ? "Omnivore" : request.getDietType());
        session.setExcludedIngredients((request.getAllergies() == null || request.getAllergies().isEmpty()) ? "No restrictions" : request.getAllergies());

        if (request.getUserId() != null) {
            User user = userRepo.findById(request.getUserId()).orElseThrow(() -> new RuntimeException("User not found"));
            session.setUser(user);
        }

        return sessionRepo.save(session).getId();
    }

    @Transactional
    public void sendMessage(Long sessionId, String userMessageText) {
        CookingSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session does not exist"));

        saveMessage(session, userMessageText, "USER");

        Long userId = session.getUser() != null ? session.getUser().getId() : null;
        String longTermMemory = retrieveLongTermMemory(userMessageText, userId);

        List<Message> aiMessages = buildConversationContext(session, longTermMemory, userMessageText);

        // Check if user provided an email address
        boolean hasEmail = userMessageText.matches(".*[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}.*");
        String aiResponse = callAiModel(aiMessages, hasEmail);

        saveMessage(session, aiResponse, "AI");
        updateLongTermMemory(session, sessionId);
    }

    private String retrieveLongTermMemory(String query, Long userId) {
        if (vectorService == null) return "";
        try {
            List<Document> similarChats = vectorService.searchSimilarConversations(query, 2, userId);
            if (similarChats.isEmpty()) return "";

            String memoryContent = similarChats.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n---\n"));
            return "\nRELEVANT PAST CONVERSATIONS (MEMORY - FOR CONTEXT ONLY, DO NOT OVERRIDE CURRENT DIET RESTRICTIONS):\n" + memoryContent + "\n";
        } catch (Exception e) {
            System.err.println("Vector search failed: " + e.getMessage());
            return "";
        }
    }

    private List<Message> buildConversationContext(CookingSession session, String memory, String currentMsg) {
        String systemText = createSystemPrompt(session, memory);

        List<ChatMessage> history = messageRepo.findBySessionIdOrderByTimestampAsc(session.getId());
        int windowSize = 20;
        if (history.size() > windowSize) {
            history = history.subList(history.size() - windowSize, history.size());
        }

        List<Message> aiMessages = new ArrayList<>();
        aiMessages.add(new SystemMessage(systemText));

        for (int i = 0; i < history.size() - 1; i++) {
            ChatMessage msg = history.get(i);
            if ("USER".equalsIgnoreCase(msg.getSender())) {
                aiMessages.add(new UserMessage(msg.getContent()));
            } else {
                aiMessages.add(new AssistantMessage(msg.getContent()));
            }
        }

        // Just pass the user's message directly - let the LLM decide what to do
        aiMessages.add(new UserMessage(currentMsg));

        return aiMessages;
    }

    private String createSystemPrompt(CookingSession session, String memory) {
        String styleGuide = getPersonaStyle(session.getChefPersonality());

        return String.format("""
            You are %s, a cooking assistant.
            
            %s
            
            User diet: %s
            Allergies: %s
            
            RULES:
            1. Use searchRecipes first. If no results, use search tool, then fetch_content.
            2. Write ALL responses as %s would talk.
            
            Past conversations: %s
            """,
                session.getChefPersonality(),
                styleGuide,
                session.getDietType(),
                session.getExcludedIngredients(),
                session.getChefPersonality(),
                memory.isEmpty() ? "None" : memory
        );
    }

    private String callAiModel(List<Message> messages, boolean includeEmailTool) {
        try {
            OllamaChatOptions.Builder optionsBuilder = OllamaChatOptions.builder()
                    .temperature(0.85);

            // Filter tools - only include email tool if user provided an email address
            if (!toolCallbacks.isEmpty()) {
                List<ToolCallback> filteredTools;
                if (includeEmailTool) {
                    filteredTools = toolCallbacks;
                } else {
                    filteredTools = toolCallbacks.stream()
                            .filter(tc -> !tc.getToolDefinition().name().equals("send-email"))
                            .collect(Collectors.toList());
                }
                System.out.println("[ChefAiService] Tools for this request: " +
                    filteredTools.stream().map(t -> t.getToolDefinition().name()).collect(Collectors.joining(", ")));
                optionsBuilder.toolCallbacks(filteredTools);
            }


            Prompt prompt = new Prompt(messages, optionsBuilder.build());
            return chatModel.call(prompt).getResult().getOutput().getText();
        } catch (Exception e) {
            e.printStackTrace();
            return "I am sorry, my kitchen is on fire (Internal Error): " + e.getMessage();
        }
    }

    private void saveMessage(CookingSession session, String content, String sender) {
        ChatMessage msg = new ChatMessage();
        msg.setContent(content);
        msg.setSender(sender);
        msg.setTimestamp(LocalDateTime.now());
        msg.setSession(session);
        messageRepo.save(msg);
    }

    private void updateLongTermMemory(CookingSession session, Long sessionId) {
        if (vectorService != null) {
            List<ChatMessage> allMessages = getMessagesForSession(sessionId);
            vectorService.saveConversationToVector(session, allMessages);
        }
    }

    public CookingSession getSessionInfo(Long id) { return sessionRepo.findById(id).orElseThrow(); }
    public List<ChatMessage> getMessagesForSession(Long sessionId) { return messageRepo.findBySessionIdOrderByTimestampAsc(sessionId); }
    public List<CookingSession> getAllSessions() { return sessionRepo.findAll(); }
    public List<CookingSession> getSessionsForUser(Long userId) { return sessionRepo.findByUserId(userId); }

    public boolean deleteSession(Long sessionId, Long userId) {
        CookingSession session = sessionRepo.findById(sessionId).orElseThrow();
        if (session.getUser() == null || !session.getUser().getId().equals(userId)) return false;
        if (vectorService != null) vectorService.deleteConversationVectors(sessionId);
        sessionRepo.delete(session);
        return true;
    }

    private String getPersonaStyle(String personality) {
        if (personality == null) return "Be helpful and polite.";

        if (personality.contains("Gordon Ramsay")) {
            return """
                SPEAK LIKE GORDON RAMSAY:
                - Be intense, loud, demanding
                - Use: "Come on!", "It's RAW!", "Donkey!", "Beautiful!", "Move it!"
                - Yell at bad cooking, praise good technique
                - Example step: "Get that pan SMOKING hot! If it's not hot, don't even THINK about cooking!"
                """;
        } else if (personality.contains("Grandma")) {
            return """
                SPEAK LIKE A LOVING GRANDMA:
                - Be warm, gentle, full of love
                - Use: "Sweetie", "Darling", "Dear", "Just like mama made"
                - Share little stories and memories
                - Example step: "Now sweetie, stir this gently with love - that's the secret ingredient!"
                """;
        } else if (personality.contains("French Chef")) {
            return """
                SPEAK LIKE A FRENCH CHEF:
                - Be elegant, sophisticated, a bit snobby
                - Use French words: "Magnifique!", "Mon ami", "Voilà", "Sacré bleu!"
                - Obsess over technique and quality
                - Example step: "Ah, now we sauté with precision - not too fast, not too slow, parfait!"
                """;
        } else if (personality.contains("Nutritionist")) {
            return """
                SPEAK LIKE A NUTRITIONIST:
                - Be informative and encouraging
                - Explain health benefits of ingredients
                - Use: "antioxidants", "protein", "vitamins", "fuel your body"
                - Example step: "Add the spinach - packed with iron for energy!"
                """;
        } else {
            return "Be a helpful, professional chef.";
        }
    }
}