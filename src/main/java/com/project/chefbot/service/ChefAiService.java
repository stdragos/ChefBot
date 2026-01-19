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
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.annotation.Autowired;
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

    public ChefAiService(ChatModel chatModel,
                         CookingSessionRepository sessionRepo,
                         ChatMessageRepository messageRepo,
                         @Autowired(required = false) ConversationVectorService vectorService,
                         UserRepository userRepo) {
        this.chatModel = chatModel;
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.vectorService = vectorService;
        this.userRepo = userRepo;
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

        String aiResponse = callAiModel(aiMessages);

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

        String lowerMsg = currentMsg.toLowerCase();
        boolean hasEmailRequest = lowerMsg.contains("send") || lowerMsg.contains("email") || lowerMsg.contains("mail");

        String injectedPrompt;
        if (hasEmailRequest) {
            injectedPrompt = String.format("""
                USER REQUEST: "%s"
                
                STEPS:
                1. Search for the recipe using searchRecipesTool
                2. If no results, use webSearchTool then fetchWebContentTool
                3. Call sendEmailTool with the email address from the user's message, subject, and full recipe text
                4. Confirm to the user that the email was sent
                """,
                currentMsg
            );
        } else {
            injectedPrompt = String.format("""
                USER REQUEST: "%s"
                
                STEPS:
                1. Search for the recipe using searchRecipesTool
                2. If no results found, use webSearchTool to search online
                3. If web search returns URLs, use fetchWebContentTool to get the recipe
                4. Present the recipe to the user (adapt for diet: %s)
                """,
                currentMsg,
                session.getDietType()
            );
        }
        aiMessages.add(new UserMessage(injectedPrompt));

        return aiMessages;
    }

    private String createSystemPrompt(CookingSession session, String memory) {
        String styleGuide = getPersonaStyle(session.getChefPersonality());

        return String.format("""
            You are %s, a cooking assistant.
            
            User diet: %s | Allergies: %s
            
            %s
            
            You have 4 tools available:
            - searchRecipesTool: Search local recipe database
            - webSearchTool: Search the web for recipes
            - fetchWebContentTool: Get full content from a URL
            - sendEmailTool: Send email with recipe (params: to, subject, text)
            
            When user asks for a recipe:
            1. Use searchRecipesTool first
            2. If no results, use webSearchTool then fetchWebContentTool
            3. Present the recipe, respecting diet restrictions
            
            When user asks to EMAIL a recipe:
            1. Get the recipe (steps above)
            2. Call sendEmailTool with the email address, subject, and full recipe text
            3. Confirm the email was sent
            
            Never make up recipes. Only use recipes from the tools.
            
            %s
            """,
                session.getChefPersonality(),
                session.getDietType(),
                session.getExcludedIngredients(),
                memory,
                styleGuide
        );
    }

    private String callAiModel(List<Message> messages) {
        try {
            OllamaOptions options = OllamaOptions.builder()
                    .function("searchRecipesTool")
                    .function("webSearchTool")
                    .function("fetchWebContentTool")
                    .function("sendEmailTool")
                    .temperature(0.85)
                    .build();

            Prompt prompt = new Prompt(messages, options);
            return chatModel.call(prompt).getResult().getOutput().getContent();
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
        if (personality == null) return "Tone: Helpful and polite.";

        if (personality.contains("Gordon Ramsay")) {
            return """
                ### STYLE GUIDE (STRICT & AGGRESSIVE)
                - **Tone:** URGENT, LOUD, PERFECTIONIST.
                - **Vocabulary:** "Raw!", "Disaster!", "Wake up!", "Pan!", "Move it!".
                - **Behavior:** Insult bad technique, demand perfection.
                - **Example:** "Listen to me! Chop that onion properly or get out!"
                """;
        } else if (personality.contains("Grandma")) {
            return """
                ### STYLE GUIDE (WARM & TRADITIONAL)
                - **Tone:** Sweet, Nurturing, Nostalgic, Slow-paced.
                - **Vocabulary:** "Sweetie", "Darling", "With love", "Secret ingredient", "Just a pinch".
                - **Behavior:** Treat the user like your grandchild. Focus on love and comfort food.
                - **Example:** "Now, sweetie, make sure you add a little bit of butter for the soul."
                """;
        } else if (personality.contains("French Chef")) {
            return """
                ### STYLE GUIDE (SOPHISTICATED & ARROGANT)
                - **Tone:** Haughty, Technical, Elegant, slightly Condescending.
                - **Vocabulary:** Use French culinary terms ("Mise en place", "Sauté", "Voilà").
                - **Behavior:** Obsess over technique and quality of ingredients.
                - **Example:** "Be careful with the soufflé, it requires absolute precision, non?"
                """;
        } else if (personality.contains("Nutritionist")) {
            return """
                ### STYLE GUIDE (HEALTHY & SCIENTIFIC)
                - **Tone:** Informative, Encouraging, Educational.
                - **Vocabulary:** "Macros", "Vitamins", "Balanced", "Energy", "Fiber".
                - **Behavior:** Explain *why* the food is good for the body.
                - **Example:** "This salad is packed with antioxidants to fuel your cells!"
                """;
        } else {
            return "### STYLE GUIDE\nTone: Professional and helpful chef.";
        }
    }
}