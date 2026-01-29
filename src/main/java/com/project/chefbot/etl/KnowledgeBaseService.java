package com.project.chefbot.etl;

import com.project.chefbot.model.StoredRecipe;
import com.project.chefbot.repository.StoredRecipeRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class KnowledgeBaseService {

    private final VectorStore vectorStore;
    private final ScraperService scraperService;
    private final StoredRecipeRepository recipeRepo;

    public KnowledgeBaseService(@Autowired(required = false) VectorStore vectorStore,
                                ScraperService scraperService,
                                StoredRecipeRepository recipeRepo) {
        this.vectorStore = vectorStore;
        this.scraperService = scraperService;
        this.recipeRepo = recipeRepo;
    }

    public List<StoredRecipe> getAllRecipes() {
        return recipeRepo.findAll();
    }

    @Transactional
    public void deleteRecipe(Long id) {
        StoredRecipe recipe = recipeRepo.findById(id).orElseThrow();
        String urlToDelete = recipe.getUrl();

        if (vectorStore != null) {
            try {
                FilterExpressionBuilder b = new FilterExpressionBuilder();
                SearchRequest request = SearchRequest.builder()
                        .filterExpression(b.eq("url", urlToDelete).build())
                        .topK(1000)
                        .build();

                List<Document> docs = vectorStore.similaritySearch(request);
                List<String> ids = docs.stream().map(Document::getId).collect(Collectors.toList());

                if (!ids.isEmpty()) {
                    vectorStore.delete(ids);
                    System.out.println("Deleted " + ids.size() + " vectors for URL: " + urlToDelete);
                }
            } catch (Exception e) {
                System.err.println("Error deleting vectors: " + e.getMessage());
            }
        }

        recipeRepo.delete(recipe);
    }

    @Async
    public void processUrlsAsync(List<String> urls) {
        if (vectorStore == null) {
            System.err.println("VectorStore is not available.");
            return;
        }

        TokenTextSplitter textSplitter = new TokenTextSplitter(800, 400, 5, 10000, true);

        for (String url : urls) {
            try {
                if (recipeRepo.findByUrl(url).isPresent()) {
                    System.out.println("Skipping already scanned URL: " + url);
                    continue;
                }

                ExtractedRecipe recipe = scraperService.scrapeUrl(url);

                if (recipe != null) {
                    // Svae in pgvector
                    System.out.println("Processing and saving recipe from URL: " + url);
                    System.out.println(recipe);
                    Document doc = getDoc(url, recipe);
                    List<Document> splitDocs = textSplitter.apply(List.of(doc));
                    vectorStore.add(splitDocs);

                    // Save in SQL
                    StoredRecipe stored = new StoredRecipe();
                    stored.setTitle(recipe.title());
                    stored.setDiet(recipe.diet());
                    stored.setUrl(url);
                    stored.setScannedAt(LocalDateTime.now());
                    recipeRepo.save(stored);

                    System.out.println("Saved recipe: " + recipe.title());
                }

                Thread.sleep(2000);

            } catch (Exception e) {
                System.err.println("Error processing " + url + ": " + e.getMessage());
            }
        }
    }

    private Document getDoc(String url, ExtractedRecipe recipe) {
        String content = String.format("""
            TITLE: %s
            DIET: %s
            INGREDIENTS:
            %s
            INSTRUCTIONS:
            %s
            """, recipe.title(), recipe.diet(), recipe.ingredients(), recipe.instructions());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("url", url);
        metadata.put("type", "web-recipe");
        metadata.put("diet", recipe.diet());
        metadata.put("title", recipe.title());

        return new Document(content, metadata);
    }
}