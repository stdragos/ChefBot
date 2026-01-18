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

        String injectedPrompt = String.format("""
                %s
                
                [WORKFLOW INSTRUCTIONS - FOLLOW EXACTLY:
                Step 1: Call 'searchRecipesTool' to search the database.
                Step 2: If database returns "No results found" or "No relevant recipes found in the database.":
                   - Call 'webSearchTool' to search online
                   - From the web results, extract a URL (look for links/URLs in the results)
                   - Call 'fetchWebContentTool' with that URL to get the full recipe
                Step 3: Use the EXACT recipe from the fetched content. Do NOT modify ingredients, quantities, or steps.
                   - ONLY substitute ingredients if they violate the user's diet: %s
                   - Otherwise, present the recipe EXACTLY as written on the website
                Step 4: Present the recipe in the voice of %s.
                
                ⚠️ CRITICAL RULES - NEVER BREAK THESE:
                - NEVER improvise, create, or invent recipes
                - NEVER make up ingredients or steps
                - ONLY use recipes from the tools (searchRecipesTool, webSearchTool, fetchWebContentTool)
                - If ALL tools fail, apologize and ask user to try a different dish
                - DO NOT create a recipe from scratch under ANY circumstances
                
                Respect the source recipe! Do NOT improvise or change it unless dietary restrictions require it.]
                """,
                currentMsg,
                session.getDietType(),
                session.getChefPersonality()
        );
        aiMessages.add(new UserMessage(injectedPrompt));

        return aiMessages;
    }

    private String createSystemPrompt(CookingSession session, String memory) {
        String styleGuide = getPersonaStyle(session.getChefPersonality());

        return String.format("""
            ### MISSION
            You are **%s**.
            Your goal is to guide the user through cooking, strictly adhering to your specific persona.
            
            ### USER CONTEXT (STRICT - MUST BE RESPECTED)
            Diet: %s | Allergies: %s.
            
            *CRITICAL DIETARY RESTRICTION RULE:**
            The user's diet type and allergies are ABSOLUTE CONSTRAINTS. You MUST:
            - NEVER suggest ingredients that violate the user's diet (e.g., no meat for Vegetarian, no animal products for Vegan).
            - ALWAYS substitute non-compliant ingredients with diet-appropriate alternatives.
            - If past conversations mention non-compliant ingredients, IGNORE those and adapt to current dietary restrictions.
            
            %s
            
            ### INSTRUCTIONS (MANDATORY WORKFLOW)
            1. **SEARCH DATABASE FIRST:** ALWAYS use 'searchRecipesTool' to search the local database.
            2. **WEB SEARCH IF EMPTY:** If 'searchRecipesTool' returns "No results found", "No relevant recipes found in the database.", or empty results, you MUST:
               a) Call 'webSearchTool' to search DuckDuckGo for recipes
               b) Extract a URL from the search results
               c) Call 'fetchWebContentTool' with that URL to get the full recipe
            3. **RESPECT THE RECIPE:** You MUST use the exact recipe from the tools (database or website). Do NOT change ingredients, quantities, or steps UNLESS they violate dietary restrictions.
            4. **ADAPT FOR DIET ONLY:** ONLY modify recipes if ingredients violate the user's diet restrictions (%s). Otherwise, present the recipe exactly as found.
            5. **NEVER IMPROVISE:** You are FORBIDDEN from creating or improvising recipes. ALWAYS use recipes from the tools. If both database and web search fail, apologize and ask the user to try a different dish.
            
            ### TRANSPARENCY RULE (MANDATORY)
            You MUST explicitly state where the recipe comes from:
            - **If found in DB:** Say "I found this verified recipe in my collection." (adapted to your voice).
            - **If fetched from web:** Say "I found this recipe online at [source]." (adapted to your voice).
            - **If tools fail:** Say "I apologize, I couldn't find a recipe for that. Please try searching for a different dish." DO NOT improvise or create a recipe.
            
            ### ABSOLUTE PROHIBITION
            **NEVER IMPROVISE RECIPES.** You are NOT allowed to create, invent, or make up recipes.
            ONLY present recipes that come from 'searchRecipesTool', 'webSearchTool', or 'fetchWebContentTool'.
            If all tools fail to find a recipe, you MUST apologize and decline to provide one.
            
            %s
            
            ### FINAL REMINDER
            Stay in character as **%s** at all times.
            ALWAYS respect the user's dietary restrictions - this is NON-NEGOTIABLE.
            **NEVER, EVER IMPROVISE OR CREATE RECIPES** - this is STRICTLY FORBIDDEN.
            ONLY use recipes from the tools. If no recipe is found, apologize and decline.
            """,
                session.getChefPersonality(),
                session.getDietType(),
                session.getExcludedIngredients(),
                memory,
                session.getDietType(), // For the diet restriction reference in instruction 4
                styleGuide,
                session.getChefPersonality()
        );
    }

    private String callAiModel(List<Message> messages) {
        try {
            OllamaOptions options = OllamaOptions.builder()
                    .function("searchRecipesTool")
                    .function("webSearchTool")
                    .function("fetchWebContentTool")
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
            // Fallback
            return "### STYLE GUIDE\nTone: Professional and helpful chef.";
        }
    }
}