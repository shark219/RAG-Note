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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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

        CompletableFuture.runAsync(() -> {
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
            }
        }, taskExecutor);

        return emitter;
    }

    private String processWithTools(String query, String userId,
                                    List<dev.langchain4j.data.message.ChatMessage> messages) {
        // Simple tool detection based on keywords
        String queryLower = query.toLowerCase();

        if (queryLower.contains("搜索") || queryLower.contains("查找") || queryLower.contains("search")) {
            String toolResult = agentTools.searchNotes(query, 5, userId);
            messages.add(UserMessage.from("搜索结果：\n" + toolResult + "\n\n基于以上结果回答用户的问题：" + query));
        } else if (queryLower.contains("复习") || queryLower.contains("review")) {
            String toolResult = agentTools.getTodayReviews(userId);
            messages.add(UserMessage.from("今日复习列表：\n" + toolResult + "\n\n基于以上信息回答用户的问题：" + query));
        } else if (queryLower.contains("时间") || queryLower.contains("几点") || queryLower.contains("time")) {
            String toolResult = agentTools.whatTimeIsNow();
            messages.add(UserMessage.from("当前时间：" + toolResult + "\n\n回答用户：" + query));
        } else if (queryLower.contains("知识库") || queryLower.contains("文档") || queryLower.contains("rag")) {
            String toolResult = agentTools.ragSummary(query, userId);
            messages.add(UserMessage.from("知识库检索结果：\n" + toolResult + "\n\n基于以上结果回答用户：" + query));
        } else if (queryLower.contains("统计") || queryLower.contains("stats")) {
            String toolResult = agentTools.getNoteStats(userId);
            messages.add(UserMessage.from("笔记统计：\n" + toolResult + "\n\n回答用户：" + query));
        } else {
            messages.add(UserMessage.from(query));
        }

        // Get LLM response
        ChatLanguageModel chatModel = modelFactory.createChatModel();
        dev.langchain4j.model.output.Response<AiMessage> response = chatModel.generate(messages);
        return response.content().text();
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
