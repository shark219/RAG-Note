package com.rag.notebook.agent;

import com.rag.notebook.chat.entity.ChatMessage;
import com.rag.notebook.chat.entity.ChatSession;
import com.rag.notebook.chat.service.ChatService;
import com.rag.notebook.config.ApplicationProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AgentService {

    private final ModelFactory modelFactory;
    private final AgentTools agentTools;
    private final ChatService chatService;
    private final ApplicationProperties props;
    private final Executor taskExecutor;

    public AgentService(ModelFactory modelFactory, AgentTools agentTools,
                        ChatService chatService, ApplicationProperties props,
                        Executor taskExecutor) {
        this.modelFactory = modelFactory;
        this.agentTools = agentTools;
        this.chatService = chatService;
        this.props = props;
        this.taskExecutor = taskExecutor;
    }

    public SseEmitter streamAgentResponse(String query, String sessionId, String userId) {
        SseEmitter emitter = new SseEmitter(120000L);

        // Capture SecurityContext from request thread for async propagation
        SecurityContext securityContext = SecurityContextHolder.getContext();

        CompletableFuture.runAsync(() -> {
            // Set SecurityContext on the async thread
            SecurityContextHolder.setContext(securityContext);
            try {
                // Load session history
                List<ChatMessage> history = chatService.getSessionMessages(sessionId);

                // Build chat messages
                List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
                messages.add(SystemMessage.from(loadSystemPrompt()));

                for (ChatMessage msg : history) {
                    if ("human".equals(msg.getRole())) {
                        messages.add(UserMessage.from(msg.getContent()));
                    } else if ("ai".equals(msg.getRole())) {
                        messages.add(AiMessage.from(msg.getContent()));
                    }
                }

                // Process tools and get response
                String response = processWithTools(query, userId, messages);

                // Send thinking event
                sendSseEvent(emitter, "thinking", Map.of(
                        "stage", "complete",
                        "content", "已处理完成"
                ));

                // Stream response in chunks for better performance
                int chunkSize = 50;
                for (int i = 0; i < response.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, response.length());
                    sendSseEvent(emitter, "response", Map.of(
                            "content", response.substring(i, end),
                            "session_id", sessionId
                    ));
                    Thread.sleep(50);
                }

                // Save to history
                chatService.addMessage(sessionId, userId, "human", query);
                chatService.addMessage(sessionId, userId, "ai", response);

                // Send done event
                sendSseEvent(emitter, "done", Map.of("session_id", sessionId));
                emitter.complete();

            } catch (Exception e) {
                log.error("Agent stream failed: {}", e.getMessage(), e);
                try {
                    sendSseEvent(emitter, "error", Map.of(
                            "content", "处理请求时发生错误: " + e.getMessage(),
                            "session_id", sessionId
                    ));
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.completeWithError(ex);
                }
            } finally {
                SecurityContextHolder.clearContext();
            }
        }, taskExecutor);

        return emitter;
    }

    private String processWithTools(String query, String userId,
                                    List<dev.langchain4j.data.message.ChatMessage> messages) {
        ChatLanguageModel chatModel = modelFactory.createChatModel();

        // Add user query
        messages.add(UserMessage.from(query));

        // Agent loop: max 3 iterations to prevent infinite loops
        for (int i = 0; i < 3; i++) {
            dev.langchain4j.model.output.Response<AiMessage> response = chatModel.generate(messages);
            String responseText = response.content().text();

            // Check if LLM is trying to call a tool
            String toolName = extractToolName(responseText);
            if (toolName == null) {
                // No tool call, return the response directly
                return responseText;
            }

            // Execute the tool
            String toolResult = executeTool(toolName, query, userId);
            log.info("Tool '{}' executed, result length: {}", toolName, toolResult.length());

            // Add AI response and tool result to conversation, then let LLM generate final answer
            messages.add(AiMessage.from(responseText));
            messages.add(UserMessage.from("工具执行结果：\n" + toolResult + "\n\n请基于以上结果，用自然语言回答用户的问题：" + query));
        }

        // If max iterations reached, just call LLM one final time
        dev.langchain4j.model.output.Response<AiMessage> finalResponse = chatModel.generate(messages);
        return finalResponse.content().text();
    }

    /**
     * Detect if LLM response contains a tool call
     */
    private String extractToolName(String response) {
        if (response == null) return null;
        // Match known tool names in the response
        String[] toolNames = {"rag_summary_tools", "search_notes_tool", "get_note_stats_tool",
                "get_today_reviews_tool", "mark_reviewed_tool", "create_note_tool", "get_related_notes_tool"};
        for (String tool : toolNames) {
            if (response.contains(tool)) {
                return tool;
            }
        }
        return null;
    }

    /**
     * Execute a tool by name
     */
    private String executeTool(String toolName, String query, String userId) {
        try {
            return switch (toolName) {
                case "rag_summary_tools" -> agentTools.ragSummary(query, userId);
                case "search_notes_tool" -> agentTools.searchNotes(query, 5, userId);
                case "get_note_stats_tool" -> agentTools.getNoteStats(userId);
                case "get_today_reviews_tool" -> agentTools.getTodayReviews(userId);
                case "mark_reviewed_tool" -> agentTools.markReviewed(query, userId);
                case "create_note_tool" -> "笔记创建功能暂不支持自动调用";
                case "get_related_notes_tool" -> "相关笔记推荐功能暂不支持自动调用";
                default -> "未知工具: " + toolName;
            };
        } catch (Exception e) {
            log.error("Tool '{}' execution failed: {}", toolName, e.getMessage());
            return "工具执行失败: " + e.getMessage();
        }
    }

    private void sendSseEvent(SseEmitter emitter, String type, Map<String, Object> data) throws IOException {
        Map<String, Object> event = new HashMap<>();
        event.put("type", type);
        event.putAll(data);
        emitter.send(SseEmitter.event().data(event));
    }

    private String loadSystemPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource("prompt/main_prompt.txt");
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Failed to load system prompt, using default");
            return "你是一个智能笔记助手，可以帮助用户管理笔记、搜索知识库、安排复习计划。请用中文回答。";
        }
    }
}
