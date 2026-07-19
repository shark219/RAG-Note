package com.rag.notebook.chat.repo;

import com.rag.notebook.chat.entity.ChatAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, String> {

    List<ChatAttachment> findBySessionIdAndStatusOrderByCreatedAtDesc(String sessionId, String status);

    List<ChatAttachment> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, String status);

    Optional<ChatAttachment> findByIdAndUserId(String id, String userId);
}
