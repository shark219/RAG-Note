package com.rag.notebook.chat.service;

import com.rag.notebook.chat.entity.ChatMessage;
import com.rag.notebook.chat.entity.ChatSession;
import com.rag.notebook.chat.repo.ChatMessageRepository;
import com.rag.notebook.chat.repo.ChatSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
public class DatabaseSessionManager {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;

    public DatabaseSessionManager(ChatSessionRepository sessionRepository,
                                  ChatMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    public ChatSession getOrCreateSession(String sessionId, String userId) {
        return sessionRepository.findById(sessionId).orElseGet(() -> {
            ChatSession session = new ChatSession();
            session.setId(sessionId);
            session.setUserId(userId);
            session.setTitle("新的对话");
            return sessionRepository.save(session);
        });
    }

    public List<ChatMessage> getSessionHistory(String sessionId, String userId) {
        ChatSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || !session.getUserId().equals(userId)) {
            return List.of();
        }
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    @Transactional
    public void addMessage(String sessionId, String userId, String role, String content) {
        ChatSession session = getOrCreateSession(sessionId, userId);

        ChatMessage message = new ChatMessage();
        message.setSession(session);
        message.setRole(role);
        message.setContent(content);
        messageRepository.save(message);

        // Update session title from first user message
        if ("human".equals(role) && "新的对话".equals(session.getTitle())) {
            String title = content.length() > 50 ? content.substring(0, 50) + "..." : content;
            session.setTitle(title);
            sessionRepository.save(session);
        }
    }

    @Transactional
    public void clearSession(String sessionId, String userId) {
        ChatSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null && session.getUserId().equals(userId)) {
            messageRepository.deleteBySessionId(sessionId);
            sessionRepository.delete(session);
        }
    }

    public List<Map<String, Object>> getUserSessions(String userId) {
        List<ChatSession> sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        return sessions.stream().map(s -> {
            Map<String, Object> info = new HashMap<>();
            info.put("session_id", s.getId());
            info.put("title", s.getTitle());
            info.put("created_at", s.getCreatedAt());
            info.put("updated_at", s.getUpdatedAt());
            return info;
        }).toList();
    }

    public List<String> getAllSessionIds() {
        return sessionRepository.findAll().stream()
                .map(ChatSession::getId)
                .toList();
    }
}
