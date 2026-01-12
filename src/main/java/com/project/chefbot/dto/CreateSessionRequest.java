package com.project.chefbot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CreateSessionRequest {

    @NotBlank(message = "Session name is required")
    @Size(min = 3, max = 50, message = "Session name must be between 3 and 50 characters")
    private String sessionName;

    @NotBlank(message = "Choose a personality")
    private String chefPersonality;

    @NotBlank(message = "Diet type is required")
    private String dietType;

    @Size(max = 200, message = "Allergies must not exceed 200 characters")
    private String allergies;

    private Long userId;

    public String getSessionName() { return sessionName; }
    public void setSessionName(String sessionName) { this.sessionName = sessionName; }

    public String getChefPersonality() { return chefPersonality; }
    public void setChefPersonality(String chefPersonality) { this.chefPersonality = chefPersonality; }

    public String getDietType() { return dietType; }
    public void setDietType(String dietType) { this.dietType = dietType; }

    public String getAllergies() { return allergies; }
    public void setAllergies(String allergies) { this.allergies = allergies; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
}