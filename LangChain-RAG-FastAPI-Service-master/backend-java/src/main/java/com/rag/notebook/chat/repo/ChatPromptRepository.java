package com.rag.notebook.chat.repo;

import com.rag.notebook.chat.entity.ChatPrompt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatPromptRepository extends JpaRepository<ChatPrompt, Long> {

    @Query("SELECT p FROM ChatPrompt p WHERE (p.userId = :userId OR p.isSystem = true) AND p.status = :status ORDER BY p.isDefault DESC, p.createdAt DESC")
    List<ChatPrompt> findByUserIdOrSystemAndStatus(@Param("userId") String userId, @Param("status") String status);

    @Query("SELECT p FROM ChatPrompt p WHERE p.userId = :userId AND p.isDefault = true AND p.status = :status")
    Optional<ChatPrompt> findUserDefaultPrompt(@Param("userId") String userId, @Param("status") String status);

    Optional<ChatPrompt> findByIdAndUserId(Long id, String userId);

    @Query("SELECT p FROM ChatPrompt p WHERE p.isSystem = true AND p.status = :status ORDER BY p.createdAt DESC")
    List<ChatPrompt> findSystemPrompts(@Param("status") String status);
}
