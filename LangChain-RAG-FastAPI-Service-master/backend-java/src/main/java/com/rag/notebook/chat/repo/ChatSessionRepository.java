package com.rag.notebook.chat.repo;

import com.rag.notebook.chat.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

    List<ChatSession> findByUserIdOrderByUpdatedAtDesc(String userId);

    @Query("SELECT s.id FROM ChatSession s WHERE s.userId = :userId")
    List<String> findIdsByUserId(@Param("userId") String userId);
}
