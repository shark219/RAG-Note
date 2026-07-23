package com.rag.notebook.agent;

import com.rag.notebook.chat.entity.ChatMessage;
import com.rag.notebook.chat.service.ChatService;
import com.rag.notebook.config.ApplicationProperties;
import com.rag.notebook.evaluation.entity.RagTrace;
import com.rag.notebook.evaluation.repository.RagTraceRepository;
import com.rag.notebook.rag.QualityReviewer;
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
    private final ContextManager contextManager;
    private final RagTraceRepository traceRepository;
    private final QualityReviewer qualityReviewer;
    private final SupervisorService supervisorService;
    private final WriterService writerService;
    private final TokenCounter tokenCounter;
    private final List<ToolSpecification> toolSpecifications;

    public AgentService(ModelFactory modelFactory, AgentTools agentTools,
                        ChatService chatService, ApplicationProperties props,
                        @Qualifier("taskExecutor") Executor taskExecutor,
                        ContextManager contextManager,
                        RagTraceRepository traceRepository,
                        QualityReviewer qualityReviewer,
                        SupervisorService supervisorService,
                        WriterService writerService,
                        TokenCounter tokenCounter) {
        this.modelFactory = modelFactory;
        this.agentTools = agentTools;
        this.chatService = chatService;
        this.props = props;
        this.taskExecutor = taskExecutor;
        this.contextManager = contextManager;
        this.traceRepository = traceRepository;
        this.qualityReviewer = qualityReviewer;
        this.supervisorService = supervisorService;
        this.writerService = writerService;
        this.tokenCounter = tokenCounter;
        // 从 @Tool 注解自动提取工具定义
        this.toolSpecifications = ToolSpecifications.toolSpecificationsFrom(agentTools);
    }

    // 知识库相关工具名
    private static final Set<String> KNOWLEDGE_TOOLS = Set.of("ragSummary");
    // 笔记相关工具名
    private static final Set<String> NOTE_TOOLS = Set.of(
            "searchNotes", "getNoteStats", "getTodayReviews",
            "markReviewed", "createNote", "getRelatedNotes");

    /**
     * 根据用户开关过滤工具列表
     */
    private List<ToolSpecification> filterTools(boolean enableKnowledge, boolean enableNotes) {
        return toolSpecifications.stream()
                .filter(tool -> {
                    String name = tool.name();
                    if (KNOWLEDGE_TOOLS.contains(name)) return enableKnowledge;
                    if (NOTE_TOOLS.contains(name)) return enableNotes;
                    return true; // 通用工具（如 whatTimeIsNow）始终可用
                })
                .toList();
    }

    public SseEmitter streamAgentResponse(String query, String sessionId, String userId) {
        return streamAgentResponse(query, sessionId, userId, false, true, true, null);
    }

    public SseEmitter streamAgentResponse(String query, String sessionId, String userId,
                                            boolean regenerate,
                                            boolean enableKnowledge, boolean enableNotes,
                                            List<String> fileIds) {
        // 多 Agent 流水线可能耗时较长，超时设为 5 分钟
        SseEmitter emitter = new SseEmitter(300000L);

        SecurityContext securityContext = SecurityContextHolder.getContext();
        String traceId = UUID.randomUUID().toString().replace("-", "");

        CompletableFuture.runAsync(() -> {
            SecurityContextHolder.setContext(securityContext);
            long startTime = System.currentTimeMillis();
            try {
                // 先保存用户消息（重新生成时跳过，因为用户消息已存在）
                if (!regenerate) {
                    chatService.addMessage(sessionId, userId, "human", query);
                }

                // 加载会话历史，滑动窗口 + 摘要压缩
                List<ChatMessage> history = chatService.getSessionMessages(sessionId);
                ChatLanguageModel chatModel = modelFactory.createBalancedModel();
                List<dev.langchain4j.data.message.ChatMessage> historyMessages =
                        contextManager.buildMessages(history, chatModel);

                // 根据用户开关过滤工具列表
                List<ToolSpecification> activeTools = filterTools(enableKnowledge, enableNotes);
                log.info("工具过滤: enableKnowledge={}, enableNotes={}, 可用工具数={}",
                        enableKnowledge, enableNotes, activeTools.size());

                // 构建附件上下文（注入给执行层，不传给 Supervisor）
                String attachmentContext = chatService.buildAttachmentContext(fileIds, userId);
                String queryWithContext = attachmentContext != null
                        ? attachmentContext + "用户问题：" + query
                        : query;

                // Supervisor 规划：判断是否需要多 Agent 流水线（只传用户原始问题）
                List<SubTask> subTasks = supervisorService.plan(query);

                String response;
                if (subTasks.size() < 2) {
                    // 简单查询或单子任务：走原有单 Agent 流程（更快）
                    List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
                    messages.add(SystemMessage.from(loadSystemPrompt()));
                    messages.addAll(historyMessages);
                    messages.add(UserMessage.from(queryWithContext));
                    response = processWithFunctionCalling(messages, userId, emitter, sessionId, query, activeTools);
                } else {
                    // 复杂查询：走多 Agent 流水线
                    response = executePipeline(subTasks, query, userId, emitter);
                }

                // 保存 AI 回复
                chatService.addMessage(sessionId, userId, "ai", response);

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

                // 获取 RAG 的 traceId，如果没有则使用 Agent 自己生成的
                String ragTraceId = agentTools.getLatestTraceId();
                String finalTraceId = ragTraceId != null ? ragTraceId : traceId;
                log.info("Agent done - ragTraceId={}, finalTraceId={}, sessionId={}", ragTraceId, finalTraceId, sessionId);

                // 计算 token 使用量
                List<ChatMessage> allHistory = chatService.getSessionMessages(sessionId);
                int usedTokens = tokenCounter.estimateEntityTokens(allHistory);
                int maxTokens = 32000; // 与 ContextManager.MAX_CONTEXT_TOKENS 一致

                Map<String, Object> doneData = new HashMap<>();
                doneData.put("session_id", sessionId);
                doneData.put("trace_id", finalTraceId);
                doneData.put("token_used", usedTokens);
                doneData.put("token_max", maxTokens);
                sendSseEvent(emitter, "done", doneData);

                // 如果没有 RAG trace，保存一个基础 trace（用于用户反馈）
                if (ragTraceId == null) {
                    try {
                        RagTrace basicTrace = new RagTrace();
                        basicTrace.setTraceId(traceId);
                        basicTrace.setUserId(userId);
                        basicTrace.setQuery(query);
                        basicTrace.setFinalAnswer(response);
                        basicTrace.setTotalLatencyMs(System.currentTimeMillis() - startTime);
                        traceRepository.save(basicTrace);
                    } catch (Exception ex) {
                        log.warn("Failed to save basic trace: {}", ex.getMessage());
                    }
                }

                safeComplete(emitter);

            } catch (Exception e) {
                log.error("Agent stream failed: {}", e.getMessage(), e);
                // 保存错误消息到会话（不让对话丢失）
                try {
                    chatService.addMessage(sessionId, userId, "ai", "处理请求时发生错误: " + e.getMessage());
                } catch (Exception ignored) {}
                try {
                    sendSseEvent(emitter, "error", Map.of(
                            "content", "处理请求时发生错误: " + e.getMessage(),
                            "session_id", sessionId
                    ));
                    safeComplete(emitter);
                } catch (IOException ex) {
                    try {
                        emitter.completeWithError(ex);
                    } catch (IllegalStateException ignored) {}
                }
            } finally {
                SecurityContextHolder.clearContext();
            }
        }, taskExecutor);

        return emitter;
    }

    /**
     * 多 Agent 流水线：并行执行子任务 → Writer 合成
     */
    private String executePipeline(List<SubTask> subTasks, String query,
                                   String userId, SseEmitter emitter) throws IOException {
        sendSseEvent(emitter, "thinking", Map.of(
                "stage", "planning",
                "content", "已拆分为 " + subTasks.size() + " 个子任务，正在并行执行"
        ));

        // 1. 并行执行各子任务
        Map<String, String> results = new LinkedHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (SubTask task : subTasks) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    sendSseEvent(emitter, "thinking", Map.of(
                            "stage", "researching",
                            "content", "[" + task.getId() + "] " + task.getLabel() + " 执行中"
                    ));

                    String result = executeSubTask(task, userId);
                    synchronized (results) {
                        results.put(task.getId(), result);
                    }

                    log.info("子任务 [{}] {} 完成, {} 字", task.getId(), task.getLabel(), result.length());
                } catch (Exception e) {
                    log.warn("子任务 [{}] 失败: {}", task.getId(), e.getMessage());
                    synchronized (results) {
                        results.put(task.getId(), "执行失败: " + e.getMessage());
                    }
                }
            }, taskExecutor);
            futures.add(future);
        }

        // 等待所有子任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 2. Writer 合成
        sendSseEvent(emitter, "thinking", Map.of(
                "stage", "writing",
                "content", "正在整合 " + results.size() + " 个子任务结果"
        ));

        String finalAnswer = writerService.synthesize(query, subTasks, results);

        // 3. 质量审查
        List<Map<String, Object>> reviewDocs = results.values().stream()
                .map(r -> Map.<String, Object>of("content", r.length() > 500 ? r.substring(0, 500) : r))
                .toList();

        if (!reviewDocs.isEmpty()) {
            QualityReviewer.ReviewResult review = qualityReviewer.reviewAnswer(query, reviewDocs, finalAnswer);
            if (!review.approved()) {
                log.info("Pipeline 回答审查未通过: {}, 重新合成", review.reason());
                // 带反馈重新合成
                finalAnswer = writerService.synthesize(
                        query + "\n\n注意：" + review.feedback(), subTasks, results);
            }
        }

        log.info("Pipeline 完成: {} 个子任务, 最终回答 {} 字", subTasks.size(), finalAnswer.length());
        return finalAnswer;
    }

    /**
     * 执行单个子任务：构造临时 Agent 循环
     */
    private String executeSubTask(SubTask task, String userId) {
        ChatLanguageModel chatModel = modelFactory.createPreciseModel();

        // 构造子任务的系统提示，明确告诉 LLM 用什么工具
        String systemPrompt = "你是一个笔记助手，正在执行一个子任务。\n\n"
                + "任务描述：" + task.getDescription() + "\n"
                + "\n可用工具说明："
                + "\n- searchNotes(query): 搜索用户的笔记，传入搜索关键词"
                + "\n- ragSummary(query): 从知识库检索文档，传入查询内容"
                + "\n- getNoteStats(): 获取笔记统计（无需参数）"
                + "\n- getTodayReviews(): 获取今日复习（无需参数）"
                + "\n- createNote(title, content): 创建笔记"
                + "\n- whatTimeIsNow(): 获取当前时间"
                + (task.getToolHint() != null ? "\n\n必须使用工具: " + task.getToolHint() : "")
                + "\n\n请直接调用工具执行任务，不要自己编造内容。";

        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(task.getDescription()));

        // 最多 2 轮工具调用（子任务应该更轻量）
        for (int i = 0; i < 2; i++) {
            Response<AiMessage> response = chatModel.generate(messages, toolSpecifications);
            AiMessage aiMessage = response.content();

            if (aiMessage.hasToolExecutionRequests()) {
                messages.add(aiMessage);
                for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                    String toolResult = executeToolWithUserId(toolRequest.name(), toolRequest.arguments(), userId);
                    messages.add(ToolExecutionResultMessage.from(toolRequest, toolResult));
                }
            } else {
                return aiMessage.text();
            }
        }

        // 达到最大轮次，强制生成
        Response<AiMessage> finalResponse = chatModel.generate(messages);
        return finalResponse.content().text();
    }

    /**
     * LangChain4j function calling 循环
     */
    private String processWithFunctionCalling(
            List<dev.langchain4j.data.message.ChatMessage> messages,
            String userId, SseEmitter emitter, String sessionId, String query,
            List<ToolSpecification> activeTools) throws IOException {

        ChatLanguageModel chatModel = modelFactory.createPreciseModel();

        // Agent 循环：最多 3 轮工具调用
        for (int i = 0; i < 3; i++) {
            // 发送消息 + 工具定义给 LLM
            Response<AiMessage> chatResponse = chatModel.generate(messages, activeTools);
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
                // LLM 不需要调工具，返回文本前做质量审查
                String answer = aiMessage.text();
                return reviewAndRetryIfNeeded(chatModel, messages, answer, emitter, query);
            }
        }

        // 达到最大轮次，最终调用一次（不带工具定义）
        Response<AiMessage> finalResponse = chatModel.generate(messages);
        String answer = finalResponse.content().text();
        return reviewAndRetryIfNeeded(chatModel, messages, answer, emitter, query);
    }

    /**
     * 回答质量审查：不达标时追加反馈重试一次
     */
    private String reviewAndRetryIfNeeded(ChatLanguageModel chatModel,
                                          List<dev.langchain4j.data.message.ChatMessage> messages,
                                          String answer, SseEmitter emitter,
                                          String query) throws IOException {
        // 提取工具结果作为参考文档
        List<Map<String, Object>> toolDocs = messages.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> {
                    String content = ((ToolExecutionResultMessage) m).text();
                    return Map.<String, Object>of("content", content.length() > 1500 ? content.substring(0, 1500) : content);
                })
                .toList();

        if (toolDocs.isEmpty()) {
            return answer;
        }

        QualityReviewer.ReviewResult review = qualityReviewer.reviewAnswer(query, toolDocs, answer);
        if (review.approved()) {
            return answer;
        }

        log.info("Agent 回答审查未通过: {}, 尝试重试", review.reason());
        sendSseEvent(emitter, "thinking", Map.of(
                "stage", "review",
                "content", "回答质量不足，正在优化: " + review.reason()
        ));

        // 追加反馈消息，让 LLM 重新生成
        messages.add(AiMessage.from(answer));
        messages.add(UserMessage.from(
                "你的回答质量不够好，请根据以下反馈重新回答：\n" + review.feedback()
                        + "\n\n请直接给出改进后的回答，不要加任何前缀语，不要调用工具。"
        ));

        try {
            // 重试时使用平衡模型，提升回答质量
            ChatLanguageModel balancedModel = modelFactory.createBalancedModel();
            Response<AiMessage> retryResponse = balancedModel.generate(messages);
            String retryAnswer = retryResponse.content().text();
            if (retryAnswer != null && !retryAnswer.isBlank()) {
                // 去掉 LLM 习惯性添加的前缀语
                retryAnswer = retryAnswer.replaceAll("^(了解您的反馈[，,].*?[：:]\n?)", "").trim();
                log.info("Agent 回答重试成功");
                return retryAnswer;
            }
        } catch (Exception e) {
            log.warn("Agent 回答重试失败: {}", e.getMessage());
        }

        return answer;
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
        try {
            emitter.send(SseEmitter.event().data(event));
        } catch (IllegalStateException e) {
            log.debug("SSE send skipped (emitter completed): type={}", type);
        } catch (IOException e) {
            // 客户端刷新/关闭页面导致的连接中断，忽略即可
            log.debug("SSE send skipped (client disconnected): type={}, msg={}", type, e.getMessage());
        }
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            log.debug("SSE emitter already completed");
        }
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
