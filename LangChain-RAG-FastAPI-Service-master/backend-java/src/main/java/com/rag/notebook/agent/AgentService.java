package com.rag.notebook.agent;

import com.rag.notebook.chat.entity.ChatMessage;
import com.rag.notebook.chat.service.ChatService;
import com.rag.notebook.config.ApplicationProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

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
    private final List<ToolSpecification> toolSpecifications;

    public AgentService(ModelFactory modelFactory, AgentTools agentTools,
                        ChatService chatService, ApplicationProperties props,
                        @Qualifier("taskExecutor") Executor taskExecutor) {
        this.modelFactory = modelFactory;
        this.agentTools = agentTools;
        this.chatService = chatService;
        this.props = props;
        this.taskExecutor = taskExecutor;
        // 从 @Tool 注解自动提取工具定义
        this.toolSpecifications = ToolSpecifications.toolSpecificationsFrom(agentTools);
    }

    public SseEmitter streamAgentResponse(String query, String sessionId, String userId) {
        SseEmitter emitter = new SseEmitter(120000L);

        SecurityContext securityContext = SecurityContextHolder.getContext();

        CompletableFuture.runAsync(() -> {
            SecurityContextHolder.setContext(securityContext);
            try {
                // 加载会话历史
                List<ChatMessage> history = chatService.getSessionMessages(sessionId);

                // 构建消息列表
                List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
                messages.add(SystemMessage.from(loadSystemPrompt()));
                for (ChatMessage msg : history) {
                    if ("human".equals(msg.getRole())) {
                        messages.add(UserMessage.from(msg.getContent()));
                    } else if ("ai".equals(msg.getRole())) {
                        messages.add(AiMessage.from(msg.getContent()));
                    }
                }
                messages.add(UserMessage.from(query));

                // 使用 function calling 处理
                String response = processWithFunctionCalling(messages, userId, emitter, sessionId);

                // 发送最终回复（流式分块）
                sendSseEvent(emitter, "thinking", Map.of(
                        "stage", "complete",
                        "content", "已处理完成"
                ));

                int chunkSize = 50;
                for (int i = 0; i < response.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, response.length());
                    sendSseEvent(emitter, "response", Map.of(
                            "content", response.substring(i, end),
                            "session_id", sessionId
                    ));
                    Thread.sleep(50);
                }

                // 保存到历史
                chatService.addMessage(sessionId, userId, "human", query);
                chatService.addMessage(sessionId, userId, "ai", response);

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

    /**
     * LangChain4j function calling 循环
     */
    private String processWithFunctionCalling(
            List<dev.langchain4j.data.message.ChatMessage> messages,
            String userId, SseEmitter emitter, String sessionId) throws IOException {

        ChatLanguageModel chatModel = modelFactory.createChatModel();

        // Agent 循环：最多 3 轮工具调用
        for (int i = 0; i < 3; i++) {
            // 发送消息 + 工具定义给 LLM
            Response<AiMessage> chatResponse = chatModel.generate(messages, toolSpecifications);
            AiMessage aiMessage = chatResponse.content();

            // LLM 要求调用工具
            if (aiMessage.hasToolExecutionRequests()) {
                String toolNames = aiMessage.toolExecutionRequests().stream()
                        .map(ToolExecutionRequest::name)
                        .reduce((a, b) -> a + ", " + b).orElse("");

                sendSseEvent(emitter, "thinking", Map.of(
                        "stage", "tool_call",
                        "content", "正在调用工具: " + toolNames
                ));

                // 把 AI 的工具调用消息加入历史
                messages.add(aiMessage);

                // 执行每个工具调用
                for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                    String toolName = toolRequest.name();
                    String toolArgs = toolRequest.arguments();
                    log.info("Executing tool: {} with args: {}", toolName, toolArgs);

                    // 注入 userId 执行工具
                    String toolResult = executeToolWithUserId(toolName, toolArgs, userId);

                    // 工具结果加入消息列表
                    messages.add(ToolExecutionResultMessage.from(toolRequest, toolResult));

                    log.info("Tool '{}' result: {}", toolName,
                            toolResult.length() > 100 ? toolResult.substring(0, 100) + "..." : toolResult);
                }
                // 继续循环，让 LLM 基于工具结果生成回复
            } else {
                // LLM 不需要调工具，直接返回文本
                return aiMessage.text();
            }
        }

        // 达到最大轮次，最终调用一次（不带工具定义）
        Response<AiMessage> finalResponse = chatModel.generate(messages);
        return finalResponse.content().text();
    }

    /**
     * 解析工具参数并注入 userId 执行
     */
    private String executeToolWithUserId(String toolName, String arguments, String userId) {
        try {
            Map<String, String> args = parseToolArguments(arguments);
            return switch (toolName) {
                case "ragSummary" -> agentTools.ragSummary(args.getOrDefault("query", ""), userId);
                case "searchNotes" -> agentTools.searchNotes(args.getOrDefault("query", ""), userId);
                case "getNoteStats" -> agentTools.getNoteStats(userId);
                case "getTodayReviews" -> agentTools.getTodayReviews(userId);
                case "markReviewed" -> agentTools.markReviewed(args.getOrDefault("noteId", ""), userId);
                case "createNote" -> agentTools.createNote(
                        args.getOrDefault("title", ""), args.getOrDefault("content", ""), userId);
                case "getRelatedNotes" -> agentTools.getRelatedNotes(args.getOrDefault("noteId", ""), userId);
                case "whatTimeIsNow" -> agentTools.whatTimeIsNow();
                default -> "未知工具: " + toolName;
            };
        } catch (Exception e) {
            log.error("Tool '{}' execution failed: {}", toolName, e.getMessage());
            return "工具执行失败: " + e.getMessage();
        }
    }

    /**
     * 解析工具调用的 JSON 参数
     */
    private Map<String, String> parseToolArguments(String json) {
        Map<String, String> result = new HashMap<>();
        if (json == null || json.isBlank()) return result;
        try {
            json = json.trim();
            if (json.startsWith("{") && json.endsWith("}")) {
                json = json.substring(1, json.length() - 1);
                for (String pair : json.split(",")) {
                    String[] kv = pair.split(":", 2);
                    if (kv.length == 2) {
                        String key = kv[0].trim().replace("\"", "");
                        String value = kv[1].trim().replace("\"", "");
                        result.put(key, value);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse tool arguments: {}", json);
        }
        return result;
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
