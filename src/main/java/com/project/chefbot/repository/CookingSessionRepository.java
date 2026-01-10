package com.project.chefbot.repository;

import com.project.chefbot.model.CookingSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CookingSessionRepository extends JpaRepository<CookingSession, Long> {

    @Query("SELECT DISTINCT s FROM CookingSession s LEFT JOIN FETCH s.messages WHERE s.id = :id")
    Optional<CookingSession> findByIdWithMessages(@Param("id") Long id);

    List<CookingSession> findByUserId(Long userId);
}
