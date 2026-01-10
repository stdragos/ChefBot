package com.project.chefbot.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Getter
    @Column(columnDefinition = "TEXT")
    private String content;

    @Setter
    @Getter
    private String sender;

    @Setter
    @Getter
    private LocalDateTime timestamp;

    @Setter
    @Getter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private CookingSession session;

    public ChatMessage() {}
}