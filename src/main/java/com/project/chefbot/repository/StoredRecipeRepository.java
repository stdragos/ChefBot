package com.project.chefbot.repository;

import com.project.chefbot.model.StoredRecipe;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface StoredRecipeRepository extends JpaRepository<StoredRecipe, Long> {
    Optional<StoredRecipe> findByUrl(String url);
}