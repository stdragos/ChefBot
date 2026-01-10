package com.project.chefbot.etl;

public record ExtractedRecipe(
        String title,
        String ingredients,
        String instructions,
        String diet
) {}
