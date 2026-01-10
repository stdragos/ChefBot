package com.project.chefbot;

import com.project.chefbot.dto.CreateSessionRequest;
import com.project.chefbot.model.ChatMessage;
import com.project.chefbot.model.CookingSession;
import com.project.chefbot.model.StoredRecipe;
import com.project.chefbot.model.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ChefbotModelTests {

    // Test 1: User default role
    @Test
    void testUserDefaultRoleIsUser() {
        User user = new User();
        assertEquals("USER", user.getRole());
    }

    // Test 2: User setters and getters
    @Test
    void testUserSettersAndGetters() {
        User user = new User();
        user.setId(1L);
        user.setUsername("chef123");
        user.setPassword("secret");
        user.setRole("ADMIN");

        assertEquals(1L, user.getId());
        assertEquals("chef123", user.getUsername());
        assertEquals("secret", user.getPassword());
        assertEquals("ADMIN", user.getRole());
    }

    // Test 3: ChatMessage creation
    @Test
    void testChatMessageCreation() {
        ChatMessage message = new ChatMessage();
        LocalDateTime now = LocalDateTime.now();

        message.setContent("Hello Chef!");
        message.setSender("USER");
        message.setTimestamp(now);

        assertEquals("Hello Chef!", message.getContent());
        assertEquals("USER", message.getSender());
        assertEquals(now, message.getTimestamp());
    }

    // Test 4: CookingSession basic properties
    @Test
    void testCookingSessionProperties() {
        CookingSession session = new CookingSession();
        session.setId(1L);
        session.setSessionName("Italian Night");
        session.setDietType("Vegetarian");
        session.setChefPersonality("Gordon Ramsay");

        assertEquals(1L, session.getId());
        assertEquals("Italian Night", session.getSessionName());
        assertEquals("Vegetarian", session.getDietType());
        assertEquals("Gordon Ramsay", session.getChefPersonality());
    }

    // Test 5: CookingSession messages never null
    @Test
    void testCookingSessionMessagesNeverNull() {
        CookingSession session = new CookingSession();
        assertNotNull(session.getMessages());
        assertTrue(session.getMessages().isEmpty());
    }

    // Test 6: CookingSession with User
    @Test
    void testCookingSessionWithUser() {
        CookingSession session = new CookingSession();
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");

        session.setUser(user);

        assertNotNull(session.getUser());
        assertEquals("testuser", session.getUser().getUsername());
    }

    // Test 7: StoredRecipe creation
    @Test
    void testStoredRecipeCreation() {
        StoredRecipe recipe = new StoredRecipe();
        LocalDateTime scannedTime = LocalDateTime.now();

        recipe.setId(1L);
        recipe.setTitle("Pasta Carbonara");
        recipe.setDiet("Omnivore");
        recipe.setUrl("https://example.com/pasta");
        recipe.setScannedAt(scannedTime);

        assertEquals(1L, recipe.getId());
        assertEquals("Pasta Carbonara", recipe.getTitle());
        assertEquals("Omnivore", recipe.getDiet());
        assertEquals("https://example.com/pasta", recipe.getUrl());
        assertEquals(scannedTime, recipe.getScannedAt());
    }

    // Test 8: CreateSessionRequest required fields
    @Test
    void testCreateSessionRequestSetters() {
        CreateSessionRequest request = new CreateSessionRequest();
        request.setSessionName("Dinner");
        request.setChefPersonality("Julia Child");
        request.setDietType("Vegan");
        request.setAllergies("nuts");
        request.setUserId(1L);

        assertEquals("Dinner", request.getSessionName());
        assertEquals("Julia Child", request.getChefPersonality());
        assertEquals("Vegan", request.getDietType());
        assertEquals("nuts", request.getAllergies());
        assertEquals(1L, request.getUserId());
    }

    // Test 9: ChatMessage with session relationship
    @Test
    void testChatMessageWithSession() {
        CookingSession session = new CookingSession();
        session.setId(1L);
        session.setSessionName("Test Session");

        ChatMessage message = new ChatMessage();
        message.setSession(session);

        assertNotNull(message.getSession());
        assertEquals(1L, message.getSession().getId());
    }

    // Test 10: CookingSession excluded ingredients
    @Test
    void testCookingSessionExcludedIngredients() {
        CookingSession session = new CookingSession();
        session.setExcludedIngredients("nuts, shellfish, gluten");

        assertEquals("nuts, shellfish, gluten", session.getExcludedIngredients());
    }
}

