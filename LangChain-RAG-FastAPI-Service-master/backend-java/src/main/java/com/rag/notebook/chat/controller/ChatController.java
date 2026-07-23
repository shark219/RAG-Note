package com.rag.notebook.chat.controller;

import com.rag.notebook.agent.AgentService;
import com.rag.notebook.agent.ClarifierService;
import com.rag.notebook.chat.dto.*;
import com.rag.notebook.chat.service.ChatService;
import com.rag.notebook.common.auth.UserId;
import com.rag.notebook.common.exception.BusinessException;
import com.rag.notebook.common.result.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;
    private final AgentService agentService;
    private final ClarifierService clarifierService;

    public ChatController(ChatService chatService, AgentService agentService, ClarifierService clarifierService) {
        this.chatService = chatService;
        this.agentService = agentService;
        this.clarifierService = clarifierService;
    }

    // ========== 会话管理 ==========

    /**
     * 获取用户所有会话列表（按更新时间倒序）
     */
    @GetMapping("/sessions")
    public ApiResponse<Map<String, Object>> getUserSessions(@UserId String userId) {
        List<Map<String, Object>> sessions = chatService.getUserSessions(userId);
        return ApiResponse.success(Map.of(
                "sessions", sessions,
                "total", sessions.size()
        ));
    }

    /**
     * 创建新会话
     */
    @PostMapping("/session/create")
    public ApiResponse<Map<String, Object>> createSession(@UserId String userId) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        chatService.createSession(sessionId, userId);
        return ApiResponse.success(Map.of(
                "session_id", sessionId,
                "title", "新对话"
        ));
    }

    /**
     * 获取单个会话详情（包含消息历史）
     */
    @GetMapping("/session/{sessionId}")
    public ApiResponse<Map<String, Object>> getSession(
            @UserId String userId,
            @PathVariable String sessionId) {
        Map<String, Object> session = chatService.getSessionDetail(sessionId, userId);
        if (session == null) {
            throw new BusinessException(404, "会话不存在");
        }
        return ApiResponse.success(session);
    }

    /**
     * 更新会话标题
     */
    @PutMapping("/session/{sessionId}/title")
    public ApiResponse<Void> updateSessionTitle(
            @UserId String userId,
            @PathVariable String sessionId,
            @RequestBody Map<String, String> request) {
        String title = request.get("title");
        if (title == null || title.isBlank()) {
            throw new BusinessException(400, "标题不能为空");
        }
        chatService.updateSessionTitle(sessionId, userId, title);
        return ApiResponse.success("标题更新成功");
    }

    /**
     * 删除单个会话
     */
    @DeleteMapping("/session/{sessionId}")
    public ApiResponse<Void> deleteSession(
            @UserId String userId,
            @PathVariable String sessionId) {
        chatService.clearSession(sessionId, userId);
        return ApiResponse.success("会话已删除");
    }

    /**
     * 批量删除会话
     */
    @DeleteMapping("/sessions/batch")
    public ApiResponse<Map<String, Object>> deleteSessions(
            @UserId String userId,
            @RequestBody Map<String, List<String>> request) {
        List<String> sessionIds = request.get("session_ids");
        if (sessionIds == null || sessionIds.isEmpty()) {
            throw new BusinessException(400, "请选择要删除的会话");
        }
        int deleted = chatService.deleteSessions(userId, sessionIds);
        return ApiResponse.success(Map.of(
                "deleted", deleted,
                "message", "已删除 " + deleted + " 个会话"
        ));
    }

    /**
     * 清空用户所有会话
     */
    @DeleteMapping("/sessions/clear")
    public ApiResponse<Map<String, Object>> clearAllSessions(@UserId String userId) {
        int deleted = chatService.clearAllSessions(userId);
        return ApiResponse.success(Map.of(
                "deleted", deleted,
                "message", "已清空所有会话"
        ));
    }

    // ========== 对话交互 ==========

    /**
     * 流式对话（SSE）
     */
    @PostMapping("/agent/query/stream")
    public SseEmitter agentQueryStream(
            @UserId String userId,
            @Valid @RequestBody QueryRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString().replace("-", "");
        }
        return agentService.streamAgentResponse(request.getQuery(), sessionId, userId,
                request.isRegenerate(), request.isEnableKnowledge(), request.isEnableNotes(),
                request.getFileIds());
    }

    /**
     * 问题澄清
     */
    @PostMapping("/clarify")
    public ApiResponse<ClarifyResult> clarify(@Valid @RequestBody QueryRequest request) {
        ClarifyResult result = clarifierService.clarify(request.getQuery());
        return ApiResponse.success(result);
    }

    /**
     * RAG 查询（非流式）
     */
    @PostMapping("/rag/query")
    public ApiResponse<Map<String, Object>> ragQuery(
            @UserId String userId,
            @Valid @RequestBody RAGRequest request) {
        String response = chatService.ragQuery(userId, request.getQuery());
        return ApiResponse.success(Map.of("response", response));
    }

    /**
     * 文档重排序
     */
    @PostMapping("/reorder")
    public ApiResponse<Map<String, Object>> reorder(@Valid @RequestBody ReorderRequest request) {
        Map<String, Object> result = chatService.reorderDocuments(request.getQuery(), request.getDocuments());
        return ApiResponse.success(result);
    }

    // ========== 消息操作 ==========

    /**
     * 删除单条消息
     */
    @DeleteMapping("/message/{messageId}")
    public ApiResponse<Void> deleteMessage(
            @UserId String userId,
            @PathVariable Long messageId) {
        chatService.deleteMessage(messageId, userId);
        return ApiResponse.success("消息已删除");
    }

    /**
     * 重新生成 AI 回复
     */
    @PostMapping("/message/{messageId}/regenerate")
    public SseEmitter regenerateMessage(
            @UserId String userId,
            @PathVariable Long messageId) {
        return chatService.regenerateMessage(messageId, userId);
    }

    // ========== 附件管理 ==========

    /**
     * 上传附件
     */
    @PostMapping("/attachment/upload")
    public ApiResponse<Map<String, Object>> uploadAttachment(
            @UserId String userId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "session_id", required = false) String sessionId) {
        Map<String, Object> result = chatService.uploadAttachment(userId, file, sessionId);
        return ApiResponse.success("上传成功", result);
    }

    /**
     * 获取会话附件列表
     */
    @GetMapping("/session/{sessionId}/attachments")
    public ApiResponse<Map<String, Object>> getSessionAttachments(
            @UserId String userId,
            @PathVariable String sessionId) {
        List<Map<String, Object>> attachments = chatService.getSessionAttachments(sessionId, userId);
        return ApiResponse.success(Map.of(
                "attachments", attachments,
                "total", attachments.size()
        ));
    }

    /**
     * 删除附件
     */
    @DeleteMapping("/attachment/{attachmentId}")
    public ApiResponse<Void> deleteAttachment(
            @UserId String userId,
            @PathVariable String attachmentId) {
        chatService.deleteAttachment(attachmentId, userId);
        return ApiResponse.success("附件已删除");
    }

    // ========== Token 统计 ==========

    /**
     * 获取会话 Token 使用量
     */
    @GetMapping("/session/{sessionId}/tokens")
    public ApiResponse<Map<String, Object>> getSessionTokens(
            @UserId String userId,
            @PathVariable String sessionId) {
        Map<String, Object> tokens = chatService.getSessionTokens(sessionId, userId);
        return ApiResponse.success(tokens);
    }

    // ========== 提示词管理 ==========

    /**
     * 获取提示词列表
     */
    @GetMapping("/prompts")
    public ApiResponse<Map<String, Object>> getPrompts(@UserId String userId) {
        List<Map<String, Object>> prompts = chatService.getPrompts(userId);
        return ApiResponse.success(Map.of(
                "prompts", prompts,
                "total", prompts.size()
        ));
    }

    /**
     * 获取默认提示词
     */
    @GetMapping("/prompts/default")
    public ApiResponse<Map<String, Object>> getDefaultPrompt(@UserId String userId) {
        Map<String, Object> prompt = chatService.getDefaultPrompt(userId);
        return ApiResponse.success(prompt);
    }
}
