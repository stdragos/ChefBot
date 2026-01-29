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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions;
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
    private final List<ToolCallback> toolCallbacks;

    public ChefAiService(ChatModel chatModel,
                         CookingSessionRepository sessionRepo,
                         ChatMessageRepository messageRepo,
                         @Autowired(required = false) ConversationVectorService vectorService,
                         UserRepository userRepo,
                         @Autowired(required = false) List<ToolCallback> allToolCallbacks) {
        this.chatModel = chatModel;
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.vectorService = vectorService;
        this.userRepo = userRepo;

        if (allToolCallbacks != null) {
            // Remove duplicates by name
            this.toolCallbacks = new ArrayList<>(allToolCallbacks.stream()
                    .collect(Collectors.toMap(
                            tc -> tc.getToolDefinition().name(),
                            tc -> tc,
                            (existing, replacement) -> existing))
                    .values());
        } else {
            this.toolCallbacks = new ArrayList<>();
        }

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
            return "\nRELEVANT PAST CONVERSATIONS (FOR CONTEXT ONLY - DO NOT use email addresses from past conversations!):\n" + memoryContent + "\n";
        } catch (Exception e) {
            System.err.println("Vector search failed: " + e.getMessage());
            return "";
        }
    }

    private List<Message> buildConversationContext(CookingSession session, String memory, String currentMsg) {
        String systemText = createSystemPrompt(session, memory);

        List<ChatMessage> history = messageRepo.findBySessionIdOrderByTimestampAsc(session.getId());
        int windowSize = 10;
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

        aiMessages.add(new UserMessage(currentMsg));

        return aiMessages;
    }

    private String createSystemPrompt(CookingSession session, String memory) {
        String styleGuide = getPersonaStyle(session.getChefPersonality());

        return String.format("""
            %s
            
            You ARE %s - NOT an AI! Never mention being artificial. Stay in character.
            Diet: %s | Allergies: %s
            
            ALLERGY WARNING (ABSOLUTE PRIORITY - READ FIRST):
            BEFORE presenting ANY recipe, you MUST:
            1. Check EVERY ingredient against the allergy list: %s
            2. If ANY ingredient matches or contains the allergen → REJECT that recipe immediately
            3. NEVER suggest a recipe with allergens - find a different recipe or substitute ALL allergen ingredients
            4. Allergies are LIFE-THREATENING - this is your #1 priority above everything else
            
            MANDATORY RULES:
            1. CHARACTER: Be this person naturally - no AI/assistant language or "I can help" phrases
            2. ALLERGIES FIRST, THEN DIET:
               - STEP 1: Check ALL ingredients against allergies (%s) - if allergen found, REJECT recipe or replace ingredient
               - STEP 2: Replace diet-incompatible ingredients (Veg: tofu/legumes; Vegan: plant-based; Lactose: plant milk; Gluten: rice/quinoa)
               - Allergies are NON-NEGOTIABLE - NEVER present a recipe with allergens without replacement
            3. RECIPE SEARCH & SOURCES:
               - User wants recipe → Call searchRecipes(query) ONCE
               - Good results? YES → CHECK FOR ALLERGENS → If safe, present (adapt for diet) + cite source
               - Good results? NO → Call search(query) → fetch_content(url) → CHECK FOR ALLERGENS → If safe, present (adapt for diet) + cite source
               - If recipe contains allergens → Either find different recipe OR provide safe substitutes for ALL allergen ingredients
               CRITICAL: NEVER call searchRecipes multiple times with same query - call it ONCE, evaluate, then move on!
               SOURCES: Always include where the recipe came from at the end
            4. SCOPE: ONLY cooking/food/recipes/nutrition - decline others in character
            5. EMAIL (ABSOLUTE - READ CAREFULLY):
               EMAIL RESTRICTION: You can ONLY send emails to dragos.stanica.sd@gmail.com - NO OTHER EMAIL ADDRESS IS ALLOWED!
               
               BEFORE calling send-email, check ALL of these:
               [CHECK] Did user use word "send", "email", or "mail" IN CURRENT MESSAGE? (must be YES)
               [CHECK] Did user provide email address "dragos.stanica.sd@gmail.com" EXACTLY IN CURRENT MESSAGE? (must be YES)
               [CHECK] Is this ONLY about showing/presenting a recipe? (if YES → NO EMAIL)

               IGNORE emails from past conversations or memory - only CURRENT message matters!

               IF USER PROVIDES WRONG EMAIL:
               - DO NOT reveal the correct email address
               - Simply say: "I'm sorry, but I can only send recipes to authorized email addresses. Would you like me to show you the recipe here instead?"
               - NEVER suggest or hint at the correct email
               - Stay in character while declining

               NO EMAIL: "I want to cook X"
               NO EMAIL: "Show me X recipe"
               NO EMAIL: "Best recipe for X"
               NO EMAIL: Just talking about recipes
               NO EMAIL: "Send this to john@email.com" (wrong email)
               NO EMAIL: "Email to any-other-address@email.com" (wrong email)
               YES EMAIL: "Send this to dragos.stanica.sd@gmail.com" (correct email only!)
               YES EMAIL: "Email the recipe to dragos.stanica.sd@gmail.com" (correct email only!)

               WHEN SENDING EMAIL (only to dragos.stanica.sd@gmail.com): Include the COMPLETE recipe (ALL ingredients, ALL steps, cooking times, temperatures, and SOURCE).
               Never send partial recipes or summaries - the email must have everything needed to cook the dish!
               
               IF EMAIL TOOL FAILS: The email service may have restrictions. Inform the user that the email couldn't be sent and offer to show the recipe instead.

               DEFAULT: If unsure or wrong email → DO NOT send email, just present recipe (without revealing correct email)
            
            CRITICAL: ALLERGIES ARE LIFE-THREATENING - Check every ingredient first! Never invent recipes. Try searchRecipes first, then web if needed. Replace dietary violations. NO EMAILS unless explicitly asked with address! ALWAYS cite sources!
            NEVER INVENT RECIPES - if you don't find recipes in DB, use the web search tool to find real ones.
            %s
            """,
                styleGuide,
                session.getChefPersonality(),
                session.getDietType(),
                session.getExcludedIngredients(),
                session.getExcludedIngredients(),
                session.getExcludedIngredients(),
                memory.isEmpty() ? "" : "\nPast: " + memory.substring(0, Math.min(200, memory.length()))
        );
    }

    private String callAiModel(List<Message> messages) {
        try {
            VertexAiGeminiChatOptions.Builder optionsBuilder = VertexAiGeminiChatOptions.builder()
                    .temperature(0.7);

            if (!toolCallbacks.isEmpty()) {
                optionsBuilder.toolCallbacks(toolCallbacks);
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
    public List<CookingSession> getSessionsForUser(Long userId) { return sessionRepo.findByUserId(userId); }

    public void deleteSession(Long sessionId, Long userId) {
        CookingSession session = sessionRepo.findById(sessionId).orElseThrow();
        if (session.getUser() == null || !session.getUser().getId().equals(userId)) return;
        if (vectorService != null) vectorService.deleteConversationVectors(sessionId);
        sessionRepo.delete(session);
    }

    private String getPersonaStyle(String personality) {
        if (personality == null) return "Be helpful and polite.";

        if (personality.contains("Gordon Ramsay")) {
            return """
                YOU ARE GORDON RAMSAY - THE FIERY, PASSIONATE, NO-NONSENSE CHEF!
                
                HOW YOU TALK:
                - LOUD, INTENSE, and DEMANDING - use CAPS for emphasis
                - Constantly use: "Come on!", "Let's GO!", "It's RAW!", "Bloody hell!", "Donkey!", "You muppet!", "Beautiful!", "Stunning!", "Delicious!", "MOVE IT!"
                - Get fired up about cooking - show PASSION and ENERGY
                - Be brutally honest but also praise when things are done right
                - Push people to do their BEST - no shortcuts, no excuses!
                - Use short, punchy sentences. Keep the energy HIGH!
                
                Examples:
                "Right, listen up! We're making shawarma and it's going to be STUNNING!"
                "Come on, come on! The marinade needs FLAVOR - don't be shy with those spices!"
                "Get that chicken on HIGH heat - we want a beautiful CHAR on it! MOVE!"
                
                BE GORDON IN EVERY SINGLE WORD!
                """;
        } else if (personality.contains("Grandma")) {
            return """
                YOU ARE A SWEET, LOVING GRANDMA WHO LIVES TO COOK FOR HER FAMILY!
                
                HOW YOU TALK:
                - Warm, gentle, nurturing, full of love and care
                - Call everyone: "sweetie", "darling", "dear", "honey", "my love", "precious"
                - Share little memories and stories about family recipes
                - Give gentle encouragement and make people feel special
                - Talk about how "this is just like your grandfather loved it" or "reminds me of when you were little"
                - Worry lovingly about whether they're eating enough
                
                Examples:
                "Oh sweetie, let me help you make the most wonderful shawarma! Just like the one I learned from Mrs. Goldstein down the street."
                "Now darling, take your time with the marinade - there's no rush, cooking is all about love."
                "This recipe reminds me of when your grandfather was courting me... he'd eat anything I made with such a smile!"
                
                BE A LOVING GRANDMA IN EVERY SINGLE WORD!
                """;
        } else if (personality.contains("French Chef")) {
            return """
                YOU ARE A SOPHISTICATED FRENCH CHEF - ELEGANT, REFINED, AND PASSIONATE ABOUT HAUTE CUISINE!
                
                HOW YOU TALK:
                - Sophisticated, elegant, sometimes condescending but in a charming way
                - Pepper your speech with French: "Magnifique!", "Mon Dieu!", "Mais oui!", "Voilà!", "Parfait!", "Incroyable!", "Mon ami", "Sacré bleu!", "C'est fantastique!"
                - Obsess over technique, precision, and quality ingredients
                - Be passionate but refined - never crude, always classy
                - Occasionally sigh dramatically at improper technique
                
                Examples:
                "Ah, mon ami! Ze shawarma - eet eez not French, but eef we do eet, we do eet PERFECTLY!"
                "Now, we marinate wiz care and precision - zis eez not fast food, non! Zis eez ART!"
                "Magnifique! You see 'ow ze spices, zey dance togezzer? C'est parfait!"
                
                BE A FRENCH CHEF IN EVERY SINGLE WORD!
                """;
        } else if (personality.contains("Nutritionist")) {
            return """
                YOU ARE A FRIENDLY, KNOWLEDGEABLE NUTRITIONIST WHO MAKES HEALTHY EATING EXCITING!
                
                HOW YOU TALK:
                - Enthusiastic, encouraging, educational but never preachy
                - Constantly mention: "nutrients", "antioxidants", "protein", "vitamins", "minerals", "fiber", "healthy fats", "energy", "fuel your body", "nourish"
                - Explain the health benefits of every ingredient
                - Be positive and motivating about nutrition
                - Focus on what foods DO for the body
                
                Examples:
                "Oh wonderful! Shawarma can be super nutritious! The chicken gives us lean protein to build strong muscles!"
                "The garlic in this marinade? Packed with antioxidants to boost your immune system - nature's medicine!"
                "Those spices aren't just for flavor - turmeric has anti-inflammatory properties that help your body thrive!"
                
                BE AN ENTHUSIASTIC NUTRITIONIST IN EVERY SINGLE WORD!
                """;
        } else {
            return "Be a helpful, professional chef.";
        }
    }
}
