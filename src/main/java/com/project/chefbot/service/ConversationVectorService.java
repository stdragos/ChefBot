package com.project.chefbot.service;

import com.project.chefbot.model.ChatMessage;
import com.project.chefbot.model.CookingSession;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ConversationVectorService {

    private final VectorStore vectorStore;

    @Autowired
    public ConversationVectorService(@Autowired(required = false) VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        if (vectorStore == null) {
            System.out.println("WARNING: VectorStore is NULL.");
        }
    }

    public void saveConversationToVector(CookingSession session, List<ChatMessage> messages) {
        if (vectorStore == null) return;

        try {
            // Delete old vectors first to prevent duplication
            deleteConversationVectors(session.getId());

            String conversationText = messages.stream()
                    .map(msg -> msg.getSender() + ": " + msg.getContent())
                    .collect(Collectors.joining("\n"));

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sessionId", session.getId());
            metadata.put("sessionName", session.getSessionName());
            metadata.put("dietType", session.getDietType());
            metadata.put("chefPersonality", session.getChefPersonality());
            metadata.put("timestamp", System.currentTimeMillis());
            if (session.getUser() != null) {
                metadata.put("userId", session.getUser().getId().toString());
            }

            Document doc = new Document(conversationText, metadata);

            TokenTextSplitter splitter = new TokenTextSplitter(800, 400, 5, 10000, true);
            List<Document> splitDocs = splitter.apply(List.of(doc));

            vectorStore.add(splitDocs);

        } catch (Exception e) {
            System.err.println("Error saving to Chroma: " + e.getMessage());
        }
    }

    public void deleteConversationVectors(Long sessionId) {
        if (vectorStore == null) return;

        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            SearchRequest searchRequest = SearchRequest.builder()
                    .filterExpression(b.eq("sessionId", sessionId).build())
                    .topK(1000)
                    .build();

            List<Document> existingDocs = vectorStore.similaritySearch(searchRequest);

            if (!existingDocs.isEmpty()) {
                List<String> idsToDelete = existingDocs.stream()
                        .map(Document::getId)
                        .collect(Collectors.toList());

                vectorStore.delete(idsToDelete);
                System.out.println("Deleted vectors for session: " + sessionId);
            }
        } catch (Exception e) {
            System.err.println("Error deleting from Chroma: " + e.getMessage());
        }
    }

    public List<Document> searchSimilarConversations(String query, int topK, Long userId) {
        if (vectorStore == null) return List.of();

        try {
            SearchRequest.Builder requestBuilder = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(0.5);

            // Filter by userId to only search current user's conversations
            if (userId != null) {
                FilterExpressionBuilder b = new FilterExpressionBuilder();
                requestBuilder.filterExpression(b.eq("userId", userId.toString()).build());
            }

            List<Document> results = vectorStore.similaritySearch(requestBuilder.build());
            System.out.println("Vector search for userId=" + userId + ", query='" + query + "' returned " + results.size() + " results");
            return results;
        } catch (Exception e) {
            System.err.println("Vector search error: " + e.getMessage());
            e.printStackTrace();
            return List.of();
        }
    }
}