package com.rag.notebook.chat.service;

import com.rag.notebook.chat.dto.SessionResponse;
import com.rag.notebook.chat.entity.ChatAttachment;
import com.rag.notebook.chat.entity.ChatMessage;
import com.rag.notebook.chat.entity.ChatPrompt;
import com.rag.notebook.chat.entity.ChatSession;
import com.rag.notebook.chat.repo.ChatAttachmentRepository;
import com.rag.notebook.chat.repo.ChatMessageRepository;
import com.rag.notebook.chat.repo.ChatPromptRepository;
import com.rag.notebook.chat.repo.ChatSessionRepository;
import com.rag.notebook.agent.AgentService;
import com.rag.notebook.common.exception.BusinessException;
import com.rag.notebook.rag.RagService;
import com.rag.notebook.rag.ReorderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.util.*;

@Slf4j
@Service
public class ChatService {

    private final DatabaseSessionManager sessionManager;
    private final ChatMessageRepository messageRepository;
    private final ChatSessionRepository sessionRepository;
    private final ChatAttachmentRepository attachmentRepository;
    private final ChatPromptRepository promptRepository;
    private final AgentService agentService;
    private final RagService ragService;
    private final ReorderService reorderService;

    @Value("${app.upload.dir:data/uploads}")
    private String uploadDir;

    public ChatService(DatabaseSessionManager sessionManager,
                       ChatMessageRepository messageRepository,
                       ChatSessionRepository sessionRepository,
                       ChatAttachmentRepository attachmentRepository,
                       ChatPromptRepository promptRepository,
                       @Lazy AgentService agentService,
                       RagService ragService,
                       ReorderService reorderService) {
        this.sessionManager = sessionManager;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.attachmentRepository = attachmentRepository;
        this.promptRepository = promptRepository;
        this.agentService = agentService;
        this.ragService = ragService;
        this.reorderService = reorderService;
    }

    // ========== 消息查询 ==========

    /**
     * 获取会话消息列表
     */
    public List<ChatMessage> getSessionMessages(String sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    /**
     * 删除单条消息
     */
    @Transactional
    public void deleteMessage(Long messageId, String userId) {
        ChatMessage message = messageRepository.findById(messageId).orElse(null);
        if (message == null) {
            throw new BusinessException(404, "消息不存在");
        }
        // 验证权限
        ChatSession session = message.getSession();
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权删除此消息");
        }
        messageRepository.delete(message);
        log.info("删除消息: messageId={}, userId={}", messageId, userId);
    }

    /**
     * 重新生成 AI 回复
     */
    public SseEmitter regenerateMessage(Long messageId, String userId) {
        ChatMessage message = messageRepository.findById(messageId).orElse(null);
        if (message == null || !"ai".equals(message.getRole())) {
            throw new BusinessException(400, "只能重新生成 AI 回复");
        }

        // 找到对应的用户问题
        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(
                message.getSession().getId());
        String userQuery = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("human".equals(messages.get(i).getRole())) {
                userQuery = messages.get(i).getContent();
                break;
            }
        }

        if (userQuery == null) {
            throw new BusinessException(400, "找不到对应的用户问题");
        }

        // 删除旧的 AI 回复
        messageRepository.delete(message);

        // 重新生成
        String sessionId = message.getSession().getId();
        return agentService.streamAgentResponse(userQuery, sessionId, userId);
    }

    // ========== 会话管理 ==========

    /**
     * 创建新会话
     */
    public void createSession(String sessionId, String userId) {
        sessionManager.getOrCreateSession(sessionId, userId);
        log.info("创建新会话: sessionId={}, userId={}", sessionId, userId);
    }

    /**
     * 获取用户所有会话列表（按更新时间倒序）
     */
    public List<Map<String, Object>> getUserSessions(String userId) {
        return sessionManager.getUserSessions(userId);
    }

    /**
     * 获取会话详情（包含消息历史）
     */
    public Map<String, Object> getSessionDetail(String sessionId, String userId) {
        ChatSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || !session.getUserId().equals(userId)) {
            return null;
        }

        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        List<Map<String, Object>> messageList = new ArrayList<>();
        for (ChatMessage msg : messages) {
            Map<String, Object> msgMap = new HashMap<>();
            msgMap.put("id", msg.getId());
            msgMap.put("role", msg.getRole());
            msgMap.put("content", msg.getContent());
            msgMap.put("created_at", msg.getCreatedAt());
            messageList.add(msgMap);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("session_id", session.getId());
        result.put("title", session.getTitle());
        result.put("created_at", session.getCreatedAt());
        result.put("updated_at", session.getUpdatedAt());
        result.put("messages", messageList);
        result.put("message_count", messageList.size());

        return result;
    }

    /**
     * 获取会话历史（兼容旧接口）
     */
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

    /**
     * 更新会话标题
     */
    @Transactional
    public void updateSessionTitle(String sessionId, String userId, String title) {
        ChatSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null && session.getUserId().equals(userId)) {
            session.setTitle(title);
            sessionRepository.save(session);
            log.info("更新会话标题: sessionId={}, title={}", sessionId, title);
        }
    }

    /**
     * 添加消息
     */
    public void addMessage(String sessionId, String userId, String role, String content) {
        sessionManager.addMessage(sessionId, userId, role, content);
    }

    /**
     * 删除单个会话
     */
    @Transactional
    public void clearSession(String sessionId, String userId) {
        sessionManager.clearSession(sessionId, userId);
        log.info("删除会话: sessionId={}, userId={}", sessionId, userId);
    }

    /**
     * 批量删除会话
     */
    @Transactional
    public int deleteSessions(String userId, List<String> sessionIds) {
        int deleted = 0;
        for (String sessionId : sessionIds) {
            ChatSession session = sessionRepository.findById(sessionId).orElse(null);
            if (session != null && session.getUserId().equals(userId)) {
                messageRepository.deleteBySessionId(sessionId);
                sessionRepository.delete(session);
                deleted++;
            }
        }
        log.info("批量删除会话: userId={}, requested={}, deleted={}", userId, sessionIds.size(), deleted);
        return deleted;
    }

    /**
     * 清空用户所有会话
     */
    @Transactional
    public int clearAllSessions(String userId) {
        List<ChatSession> sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        int deleted = 0;
        for (ChatSession session : sessions) {
            messageRepository.deleteBySessionId(session.getId());
            sessionRepository.delete(session);
            deleted++;
        }
        log.info("清空所有会话: userId={}, deleted={}", userId, deleted);
        return deleted;
    }

    // ========== 附件管理 ==========

    /**
     * 上传附件
     */
    public Map<String, Object> uploadAttachment(String userId, MultipartFile file, String sessionId) {
        if (file.isEmpty()) {
            throw new BusinessException(400, "文件不能为空");
        }

        // 检查文件大小（10MB）
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new BusinessException(400, "文件大小不能超过 10MB");
        }

        String originalName = file.getOriginalFilename();
        String extension = "";
        if (originalName != null && originalName.contains(".")) {
            extension = originalName.substring(originalName.lastIndexOf("."));
        }
        String storedName = UUID.randomUUID().toString() + extension;

        // 创建上传目录
        File uploadDirFile = new File(uploadDir + "/chat");
        if (!uploadDirFile.exists()) {
            uploadDirFile.mkdirs();
        }

        String filePath = uploadDirFile.getAbsolutePath() + "/" + storedName;
        try {
            file.transferTo(new File(filePath));
        } catch (IOException e) {
            throw new BusinessException(500, "文件上传失败: " + e.getMessage());
        }

        ChatAttachment attachment = new ChatAttachment();
        attachment.setId(UUID.randomUUID().toString());
        attachment.setUserId(userId);
        attachment.setSessionId(sessionId);
        attachment.setOriginalName(originalName);
        attachment.setStoredName(storedName);
        attachment.setFilePath(filePath);
        attachment.setFileSize(file.getSize());
        attachment.setContentType(file.getContentType());
        attachment = attachmentRepository.save(attachment);

        Map<String, Object> result = new HashMap<>();
        result.put("id", attachment.getId());
        result.put("name", originalName);
        result.put("size", file.getSize());
        result.put("content_type", file.getContentType());
        result.put("created_at", attachment.getCreatedAt());

        log.info("上传附件: userId={}, file={}, size={}", userId, originalName, file.getSize());
        return result;
    }

    /**
     * 获取会话附件列表
     */
    public List<Map<String, Object>> getSessionAttachments(String sessionId, String userId) {
        List<ChatAttachment> attachments = attachmentRepository.findBySessionIdAndStatusOrderByCreatedAtDesc(sessionId, "active");
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatAttachment att : attachments) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", att.getId());
            item.put("name", att.getOriginalName());
            item.put("size", att.getFileSize());
            item.put("content_type", att.getContentType());
            item.put("created_at", att.getCreatedAt());
            result.add(item);
        }
        return result;
    }

    /**
     * 删除附件
     */
    @Transactional
    public void deleteAttachment(String attachmentId, String userId) {
        ChatAttachment attachment = attachmentRepository.findByIdAndUserId(attachmentId, userId)
                .orElseThrow(() -> new BusinessException(404, "附件不存在"));

        // 软删除
        attachment.setStatus("deleted");
        attachmentRepository.save(attachment);
        log.info("删除附件: attachmentId={}, userId={}", attachmentId, userId);
    }

    // ========== 提示词管理 ==========

    /**
     * 获取提示词列表
     */
    public List<Map<String, Object>> getPrompts(String userId) {
        List<ChatPrompt> prompts = promptRepository.findByUserIdOrSystemAndStatus(userId, "active");
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatPrompt prompt : prompts) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", prompt.getId());
            item.put("name", prompt.getName());
            item.put("content", prompt.getContent());
            item.put("type", prompt.getType());
            item.put("is_default", prompt.getIsDefault());
            item.put("is_system", prompt.getIsSystem());
            item.put("created_at", prompt.getCreatedAt());
            item.put("updated_at", prompt.getUpdatedAt());
            result.add(item);
        }
        return result;
    }

    /**
     * 获取默认提示词
     */
    public Map<String, Object> getDefaultPrompt(String userId) {
        // 先找用户的默认提示词
        Optional<ChatPrompt> userDefault = promptRepository.findUserDefaultPrompt(userId, "active");
        if (userDefault.isPresent()) {
            return Map.of(
                    "id", userDefault.get().getId(),
                    "name", userDefault.get().getName(),
                    "content", userDefault.get().getContent()
            );
        }

        // 再找系统默认提示词
        List<ChatPrompt> systemPrompts = promptRepository.findSystemPrompts("active");
        for (ChatPrompt prompt : systemPrompts) {
            if (prompt.getIsDefault()) {
                return Map.of(
                        "id", prompt.getId(),
                        "name", prompt.getName(),
                        "content", prompt.getContent()
                );
            }
        }

        // 返回通用默认提示词
        return Map.of(
                "id", 0,
                "name", "默认通用提示词",
                "content", "你是一个智能笔记助手，可以帮助用户管理笔记、搜索知识库。请用中文回答。"
        );
    }

    // ========== 查询 ==========

    public List<String> getAllSessionIds() {
        return sessionManager.getAllSessionIds();
    }

    public String ragQuery(String userId, String query) {
        return ragService.ragSummary(userId, query);
    }

    public Map<String, Object> reorderDocuments(String query, List<String> documents) {
        return reorderService.reorderDocuments(query, documents);
    }

    // ========== Token 统计 ==========

    /**
     * 获取会话 Token 使用量
     */
    public Map<String, Object> getSessionTokens(String sessionId, String userId) {
        ChatSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || !session.getUserId().equals(userId)) {
            return Map.of("used", 0, "max", 32000, "percentage", 0);
        }

        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        // 简单估算：中文约 1.5 token/字，英文约 0.75 token/word
        int totalTokens = 0;
        for (ChatMessage msg : messages) {
            String content = msg.getContent();
            if (content != null) {
                // 粗略估算
                int chineseChars = 0;
                int englishWords = 0;
                for (char c : content.toCharArray()) {
                    if (Character.toString(c).matches("[\\u4e00-\\u9fa5]")) {
                        chineseChars++;
                    }
                }
                englishWords = content.split("\\s+").length - chineseChars;
                totalTokens += (int) (chineseChars * 1.5 + englishWords * 0.75);
            }
        }

        int maxTokens = 32000;
        int percentage = (int) ((double) totalTokens / maxTokens * 100);

        return Map.of(
                "used", totalTokens,
                "max", maxTokens,
                "percentage", Math.min(percentage, 100)
        );
    }
}
