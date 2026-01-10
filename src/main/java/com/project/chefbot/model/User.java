package com.project.chefbot.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String password;

    @Column(nullable = false)
    private String role = "USER"; // Default role is USER, can be ADMIN
}