package com.project.chefbot.etl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

@Service
public class ScraperService {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public ScraperService(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    public ExtractedRecipe scrapeUrl(String url) {
        System.out.println("[Scraper] Navigating to: " + url);

        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {

            Page page = browser.newPage();

            page.setExtraHTTPHeaders(java.util.Map.of(
                    "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            ));

            page.navigate(url);

            page.waitForTimeout(2000);

            String rawText = page.locator("body").innerText();

            System.out.println("[Scraper] Extracted text (" + rawText.length() + " chars). Sending to AI...");

            return extractRecipeWithAI(rawText);

        } catch (Exception e) {
            System.err.println("[Scraper] Playwright Error: " + e.getMessage());
            return null;
        }
    }

    private ExtractedRecipe extractRecipeWithAI(String pageText) {
        String promptText = String.format("""
            You are an expert culinary data extractor and translator.
            Your goal is to extract recipe data from the raw text below and convert it into a structured JSON in ENGLISH.
            
            --- RAW TEXT START ---
            %s
            --- RAW TEXT END ---
            
            TASK:
            1. Extract the Title, Ingredients, Instructions, and Diet.
            2. TRANSLATE EVERYTHING TO ENGLISH. The output must contain NO foreign text.
            3. Return a valid JSON.
            
            CRITICAL TITLE RULES (MUST FOLLOW):
                - **FORCE TRANSLATION**: Do not treat the title as a proper noun. Translate the *meaning* of the words.
                - **NO ORIGINAL NAMES**: Do not output the foreign name.
                - **EXAMPLES**:
                   - Input: "Tort cu ciocolata" -> Output: "Chocolate Cake" (NOT "Gâteau au Chocolat")
                   - Input: "Cozonac" -> Output: "Sweet Bread" (NOT "Cozonac")
                   - Input: "Spaghete cu scoici" -> Output: "Spaghetti with Clams"
            
            CRITICAL RULES FOR INSTRUCTIONS (READ CAREFULLY):
            - **NO SUMMARIZATION**: You must extract EVERY single action mentioned. Do not skip "small" steps like "Preheat oven" or "Let it cool".
            - **SPLIT COMPLEX STEPS**: If a sentence says "Mix the eggs and sugar, then add the flour and fold gently", split this into logical steps in the array.
            - **VERBOSITY**: It is better to have 20 short steps than 3 long summarized steps.
            - **SEQUENCE**: Maintain the exact chronological order of the recipe.

            CRITICAL CLEANING RULES:
                - **REMOVE ARTIFACTS**: Remove all checkboxes (▢, □, ☑), bullet points (•, -), emojis, or UI symbols from the start of lines.
                - **REMOVE ADS/EXTRANEOUS TEXT**: If you see any text that is clearly an advertisement, user comment, or unrelated to the recipe, exclude it entirely.
            
            JSON STRUCTURE (STRICT):
            {
              "title": "THE TRANSLATED ENGLISH TITLE (String)",
              "ingredients": ["1 cup milk", "200g flour", "..."],
              "instructions": [
                 "Preheat the oven to 180C.",
                 "Grease a baking pan.",
                 "In a large bowl, mix the eggs and sugar.",
                 "..."
              ],
              "diet": "Diet Type (must be Vegetarian, Vegan, Keto, Omnivore)"
            }
            
            OUTPUT RULES:
            - Output ONLY the raw JSON string.
            - No markdown formatting (no ```json).
            - No conversational text.
            - Reread the CRITICAL RULES above to avoid common mistakes.
            - The title MUST be in English. If the original title is in another language, translate it properly.
            """, pageText);

        try {
            String jsonResponse = chatModel.call(new Prompt(promptText)).getResult().getOutput().getText();

            System.out.println("[Scraper] Extracted recipe (" + jsonResponse + ").");

            int start = jsonResponse.indexOf("{");
            int end = jsonResponse.lastIndexOf("}");

            if (start != -1 && end != -1) {
                jsonResponse = jsonResponse.substring(start, end + 1);
            } else {
                System.err.println("[Scraper] AI did not return valid JSON. Response: " + jsonResponse);
                return null;
            }

            JsonNode root = objectMapper.readTree(jsonResponse);

            if (root.has("title") && !root.get("title").asText().equalsIgnoreCase("null")) {
                var extractedRecipe = new ExtractedRecipe(
                        root.get("title").asText(),
                        safeGetContent(root.get("ingredients")),
                        safeGetContent(root.get("instructions")),
                        root.get("diet").asText()
                );

                System.out.println("[Scraper] Extracted Recipe: " + extractedRecipe);

                return extractedRecipe;
            }
        } catch (Exception e) {
            System.err.println("[Scraper] AI Parsing Error: " + e.getMessage());
        }
        return null;
    }

    private String safeGetContent(JsonNode node) {
        if (node == null || node.isNull()) return "";

        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : node) {
                sb.append("- ").append(item.asText()).append("\n");
            }
            return sb.toString();
        }

        return node.asText();
    }
}