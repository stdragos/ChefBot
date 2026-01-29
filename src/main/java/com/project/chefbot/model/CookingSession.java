package com.project.chefbot.model;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
public class CookingSession {
    @Getter
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Getter
    private String sessionName;
    @Getter
    private String dietType;
    @Getter
    private String excludedIngredients;
    @Getter
    private String chefPersonality;

    @Getter
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("timestamp ASC")
    private List<ChatMessage> messages = new ArrayList<>();

    @Getter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    public CookingSession() {}

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public void setId(Long id) { this.id = id; }

    public void setSessionName(String sessionName) { this.sessionName = sessionName; }

    public void setDietType(String dietType) { this.dietType = dietType; }

    public void setExcludedIngredients(String excludedIngredients) { this.excludedIngredients = excludedIngredients; }

    public void setChefPersonality(String chefPersonality) { this.chefPersonality = chefPersonality; }

    public List<ChatMessage> getMessages() {
        if (messages == null) {
            messages = new ArrayList<>();
        }
        return messages;
    }
    public void setMessages(List<ChatMessage> messages) { this.messages = messages; }

    public void setUser(User user) { this.user = user; }

    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}