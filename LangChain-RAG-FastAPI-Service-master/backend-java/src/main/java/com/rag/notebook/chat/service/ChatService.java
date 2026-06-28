package com.rag.notebook.chat.service;

import com.rag.notebook.chat.dto.SessionResponse;
import com.rag.notebook.chat.entity.ChatMessage;
import com.rag.notebook.chat.repo.ChatMessageRepository;
import com.rag.notebook.rag.RagService;
import com.rag.notebook.rag.ReorderService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ChatService {

    private final DatabaseSessionManager sessionManager;
    private final ChatMessageRepository messageRepository;
    private final RagService ragService;
    private final ReorderService reorderService;

    public ChatService(DatabaseSessionManager sessionManager,
                       ChatMessageRepository messageRepository,
                       RagService ragService, ReorderService reorderService) {
        this.sessionManager = sessionManager;
        this.messageRepository = messageRepository;
        this.ragService = ragService;
        this.reorderService = reorderService;
    }

    public List<ChatMessage> getSessionMessages(String sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    public void addMessage(String sessionId, String userId, String role, String content) {
        sessionManager.getOrCreateSession(sessionId, userId);
        sessionManager.addMessage(sessionId, userId, role, content);
    }

    public SessionResponse getSessionHistory(String sessionId, String userId) {
        List<ChatMessage> messages = sessionManager.getSessionHistory(sessionId, userId);
        List<String[]> history = new ArrayList<>();
        String humanMsg = null;
        for (ChatMessage msg : messages) {
            if ("human".equals(msg.getRole())) {
                humanMsg = msg.getContent();
            } else if ("ai".equals(msg.getRole()) && humanMsg != null) {
                history.add(new String[]{humanMsg, msg.getContent()});
                humanMsg = null;
            }
        }
        return new SessionResponse(sessionId, history);
    }

    public void clearSession(String sessionId, String userId) {
        sessionManager.clearSession(sessionId, userId);
    }

    public List<Map<String, Object>> getUserSessions(String userId) {
        return sessionManager.getUserSessions(userId);
    }

    public List<String> getAllSessionIds() {
        return sessionManager.getAllSessionIds();
    }

    public String ragQuery(String userId, String query) {
        return ragService.ragSummary(userId, query);
    }

    public Map<String, Object> reorderDocuments(String query, List<String> documents) {
        return reorderService.reorderDocuments(query, documents);
    }
}
