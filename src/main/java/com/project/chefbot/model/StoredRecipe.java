package com.project.chefbot.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
public class StoredRecipe {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private String diet;

    @Column(length = 1000)
    private String url;

    private LocalDateTime scannedAt;
}