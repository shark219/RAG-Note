package com.rag.notebook.chat.controller;

import com.rag.notebook.agent.AgentService;
import com.rag.notebook.chat.dto.*;
import com.rag.notebook.chat.service.ChatService;
import com.rag.notebook.common.auth.UserId;
import com.rag.notebook.common.exception.BusinessException;
import com.rag.notebook.common.result.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;
    private final AgentService agentService;

    public ChatController(ChatService chatService, AgentService agentService) {
        this.chatService = chatService;
        this.agentService = agentService;
    }

    @PostMapping("/agent/query/stream")
    public SseEmitter agentQueryStream(@UserId String userId, @Valid @RequestBody QueryRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString().replace("-", "");
        }
        return agentService.streamAgentResponse(request.getQuery(), sessionId, userId);
    }

    @PostMapping("/rag/query")
    public ApiResponse<Map<String, Object>> ragQuery(@UserId String userId, @Valid @RequestBody RAGRequest request) {
        String response = chatService.ragQuery(userId, request.getQuery());
        return ApiResponse.success(Map.of("response", response));
    }

    @GetMapping("/session/{sessionId}")
    public ApiResponse<SessionResponse> getSession(@UserId String userId, @PathVariable String sessionId) {
        SessionResponse response = chatService.getSessionHistory(sessionId, userId);
        return ApiResponse.success(response);
    }

    @DeleteMapping("/session/{sessionId}")
    public ApiResponse<Void> deleteSession(@UserId String userId, @PathVariable String sessionId) {
        chatService.clearSession(sessionId, userId);
        return ApiResponse.success("Session " + sessionId + " deleted successfully");
    }

    @GetMapping("/sessions")
    public ApiResponse<Map<String, Object>> getAllSessions() {
        List<String> sessions = chatService.getAllSessionIds();
        return ApiResponse.success(Map.of("sessions", sessions));
    }

    @GetMapping("/sessions/{userId}")
    public ApiResponse<Map<String, Object>> getUserSessions(
            @UserId String currentUserId, @PathVariable String userId) {
        if (!currentUserId.equals(userId)) {
            throw new BusinessException(403, "无权访问其他用户的会话");
        }
        List<Map<String, Object>> sessions = chatService.getUserSessions(userId);
        return ApiResponse.success(Map.of("sessions", sessions));
    }

    @PostMapping("/reorder")
    public ApiResponse<Map<String, Object>> reorder(@Valid @RequestBody ReorderRequest request) {
        Map<String, Object> result = chatService.reorderDocuments(request.getQuery(), request.getDocuments());
        return ApiResponse.success(result);
    }
}
