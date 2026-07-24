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
    private final AgentLoop agentLoop;
    private final ResponseComposer responseComposer;
    private final ConversationContextManager convCtxManager;
    private final List<ToolSpecification> toolSpecifications;

    public AgentService(ModelFactory modelFactory, AgentTools agentTools,
                        ChatService chatService, ApplicationProperties props,
                        @Qualifier("taskExecutor") Executor taskExecutor,
                        ContextManager contextManager,
                        RagTraceRepository traceRepository,
                        QualityReviewer qualityReviewer,
                        SupervisorService supervisorService,
                        WriterService writerService,
                        TokenCounter tokenCounter,
                        AgentLoop agentLoop,
                        ResponseComposer responseComposer,
                        ConversationContextManager ctxManager) {
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
        this.agentLoop = agentLoop;
        this.responseComposer = responseComposer;
        this.convCtxManager = ctxManager;
        // 从 @Tool 注解自动提取工具定义
        this.toolSpecifications = ToolSpecifications.toolSpecificationsFrom(agentTools);
    }

    // 知识库相关工具名
    private static final Set<String> KNOWLEDGE_TOOLS = Set.of("ragSummary");
    // 笔记相关工具名
    private static final Set<String> NOTE_TOOLS = Set.of(
            "listNotes", "getNote", "searchNotes", "getRecentNotes", "getNoteStats",
            "getTodayReviews", "markReviewed", "createNote", "editNote", "appendNote",
            "deleteNote", "getRelatedNotes", "mergeNotes", "scheduleReview");
    // 通用工具名（不受开关控制）
    private static final Set<String> UTILITY_TOOLS = Set.of(
            "whatTimeIsNow", "fetchUrl", "generateDiagram");

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

                // Supervisor 规划 + 依赖分析
                List<SubTask> subTasks = supervisorService.plan(query);

                String response;
                AgentState finalAgentState = null;
                String systemPrompt = loadSystemPrompt();

                if (subTasks.isEmpty()) {
                    String resolvedQuery = convCtxManager.resolveReferences(queryWithContext, sessionId);

                    AgentLoopResult loopResult = agentLoop.run(systemPrompt, resolvedQuery,
                            historyMessages, userId, sessionId, activeTools, emitter,
                            null, null);
                    finalAgentState = loopResult.state();
                    response = composeAndReview(loopResult, query, emitter);
                } else {
                    String mode = subTasks.get(0).getExecutionMode();

                    if ("SEQUENTIAL".equals(mode)) {
                        log.info("执行模式: SEQUENTIAL, {} 个子任务", subTasks.size());
                        PipeResult pr = runSequentialPipeline(systemPrompt, queryWithContext,
                                historyMessages, userId, sessionId, activeTools, emitter,
                                subTasks);
                        response = pr.response();
                        finalAgentState = pr.state();
                    } else {
                        log.info("执行模式: PARALLEL, {} 个子任务", subTasks.size());
                        PipeResult pr = runParallelPipeline(systemPrompt, queryWithContext,
                                historyMessages, userId, sessionId, activeTools, emitter,
                                subTasks);
                        response = pr.response();
                        finalAgentState = pr.state();
                    }
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

                // 产物数据（思维导图、图表等）
                if (finalAgentState != null) {
                    List<Artifact> artifacts = finalAgentState.getArtifacts();
                    if (artifacts != null && !artifacts.isEmpty()) {
                        List<Map<String, Object>> artifactList = new ArrayList<>();
                        for (Artifact a : artifacts) {
                            Map<String, Object> am = new HashMap<>();
                            am.put("type", a.type());
                            am.put("id", a.id());
                            am.put("label", a.label());
                            if (a.metadata() != null) {
                                am.putAll(a.metadata());
                            }
                            artifactList.add(am);
                        }
                        doneData.put("artifacts", artifactList);
                    }
                }

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

    // ========== 统一管道 ==========

    /** 管道执行结果：回答 + Agent 状态（含产物数据） */
    private record PipeResult(String response, AgentState state) {}

    /**
     * 顺序执行管道：单 Agent 按目标链依次完成
     */
    private PipeResult runSequentialPipeline(String systemPrompt, String query,
                                          List<dev.langchain4j.data.message.ChatMessage> historyMessages,
                                          String userId, String sessionId,
                                          List<ToolSpecification> activeTools,
                                          SseEmitter emitter,
                                          List<SubTask> subTasks) throws IOException {
        // 合并所有目标为一个目标链
        StringBuilder goalBlock = new StringBuilder();
        goalBlock.append("\n\n[目标序列] 你需要按顺序完成以下目标：\n");
        java.util.List<String> allCriteria = new java.util.ArrayList<>();
        for (int i = 0; i < subTasks.size(); i++) {
            SubTask t = subTasks.get(i);
            goalBlock.append((i + 1)).append(". ").append(t.getGoal());
            if (t.getSuccessCriteria() != null && !t.getSuccessCriteria().isEmpty()) {
                goalBlock.append(" [标准: ").append(String.join(", ", t.getSuccessCriteria())).append("]");
                allCriteria.addAll(t.getSuccessCriteria());
            }
            goalBlock.append("\n");
        }
        goalBlock.append("\n完成一个目标后自动推进到下一个。全部完成后回答用户。");

        String fullPrompt = systemPrompt + goalBlock;
        String mergedGoal = subTasks.stream()
                .map(SubTask::getGoal)
                .reduce((a, b) -> a + "；然后" + b).orElse("");

        String resolvedQuery = convCtxManager.resolveReferences(query, sessionId);

        sendSseEvent(emitter, "thinking", Map.of(
                "stage", "planning",
                "content", "顺序执行 " + subTasks.size() + " 个目标"
        ));

        AgentLoopResult loopResult = agentLoop.run(fullPrompt, resolvedQuery,
                historyMessages, userId, sessionId, activeTools, emitter,
                mergedGoal, allCriteria);

        String answer = composeAndReview(loopResult, query, emitter);
        return new PipeResult(answer, loopResult.state());
    }

    /**
     * 并行执行管道：多 Agent 并行处理独立子任务
     */
    private PipeResult runParallelPipeline(String systemPrompt, String query,
                                        List<dev.langchain4j.data.message.ChatMessage> historyMessages,
                                        String userId, String sessionId,
                                        List<ToolSpecification> activeTools,
                                        SseEmitter emitter,
                                        List<SubTask> subTasks) throws IOException {

        sendSseEvent(emitter, "thinking", Map.of(
                "stage", "planning",
                "content", "并行执行 " + subTasks.size() + " 个子任务"
        ));

        // 并行启动各子 Agent
        record SubResult(String taskId, String label, String content, AgentState state) {}
        List<SubResult> results = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (SubTask task : subTasks) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    String taskPrompt = systemPrompt
                            + "\n\n[当前任务目标] " + task.getGoal()
                            + (task.getSuccessCriteria() != null && !task.getSuccessCriteria().isEmpty()
                               ? "\n[成功标准] " + String.join("、", task.getSuccessCriteria()) : "")
                            + "\n请专注于完成此目标，完成后直接输出结果。";

                    AgentLoopResult r = agentLoop.run(taskPrompt, query,
                            historyMessages, userId, sessionId, activeTools, emitter,
                            task.getGoal(), task.getSuccessCriteria());

                    // Composer 生成子任务的回答片段
                    String content = responseComposer.compose(EvidencePack.from(r.state()), r.outcome());

                    results.add(new SubResult(task.getId(), task.getLabel(), content, r.state()));
                    log.info("并行子任务 [{}] {} 完成, {} 字", task.getId(), task.getLabel(),
                            content.length());
                } catch (Exception e) {
                    log.warn("并行子任务 [{}] 失败: {}", task.getId(), e.getMessage());
                    results.add(new SubResult(task.getId(), task.getLabel(),
                            "执行失败: " + e.getMessage(), null));
                }
            }, taskExecutor));
        }

        // 等待全部完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 合并 AgentState（产物、证据等）
        AgentState mergedState = new AgentState(query);
        for (SubResult sr : results) {
            if (sr.state() != null) {
                mergeState(mergedState, sr.state());
            }
        }

        sendSseEvent(emitter, "thinking", Map.of(
                "stage", "composing",
                "content", "正在整合 " + results.size() + " 个子任务结果"
        ));

        // Writer 合成最终回答
        String answer = synthesizeResults(query, subTasks, results);
        log.info("并行管道完成: {} 个子任务, 最终回答 {} 字", subTasks.size(), answer.length());
        return new PipeResult(answer, mergedState);
    }

    /**
     * Composer + 质量审查
     */
    private String composeAndReview(AgentLoopResult loopResult, String query,
                                     SseEmitter emitter) throws IOException {
        sendSseEvent(emitter, "thinking", Map.of(
                "stage", "composing",
                "content", "正在组织回答"
        ));

        EvidencePack pack = EvidencePack.from(loopResult.state());
        String answer = responseComposer.compose(pack, loopResult.outcome());

        log.info("Composer 完成: outcome={}, 证据笔记 {} 篇, 产物 {} 个, 回答 {} 字",
                loopResult.outcome(), pack.notes().size(),
                pack.artifacts() != null ? pack.artifacts().size() : 0, answer.length());

        // 质量审查
        var review = qualityReviewer.reviewAnswer(query,
                pack.notes().stream()
                        .map(n -> Map.<String, Object>of("content", n.content() != null
                                ? n.content().substring(0, Math.min(500, n.content().length())) : ""))
                        .collect(java.util.stream.Collectors.toList()),
                answer);

        if (!review.approved()) {
            log.info("质量审查未通过: {}, 重新生成", review.reason());
            answer = responseComposer.compose(pack, loopResult.outcome());
        }

        return answer;
    }

    /**
     * 合并多个 AgentState 到一个
     */
    private void mergeState(AgentState target, AgentState source) {
        for (var fact : source.getWorkingMemory()) {
            target.addKnownFact(fact);
        }
        for (var artifact : source.getArtifacts()) {
            target.addArtifact(artifact);
        }
        if (source.hasWriteConfirmation()) {
            target.markWriteConfirmation(source.getWriteConfirmation());
        }
    }

    /**
     * 用 WriterService 合成多个并行子任务的结果
     */
    private String synthesizeResults(String query, List<SubTask> subTasks,
                                      List<?> results) {
        Map<String, String> resultMap = new LinkedHashMap<>();
        for (Object obj : results) {
            try {
                var method = obj.getClass().getMethod("taskId");
                var contentMethod = obj.getClass().getMethod("content");
                resultMap.put((String) method.invoke(obj), (String) contentMethod.invoke(obj));
            } catch (Exception ignored) {}
        }

        String answer = writerService.synthesize(query, subTasks, resultMap);

        // 质量审查
        List<Map<String, Object>> reviewDocs = resultMap.values().stream()
                .map(r -> Map.<String, Object>of("content", r.length() > 500 ? r.substring(0, 500) : r))
                .toList();

        if (!reviewDocs.isEmpty()) {
            var review = qualityReviewer.reviewAnswer(query, reviewDocs, answer);
            if (!review.approved()) {
                log.info("并行管道审查未通过: {}, 重新合成", review.reason());
                answer = writerService.synthesize(
                        query + "\n\n注意：" + review.feedback(), subTasks, resultMap);
            }
        }
        return answer;
    }

    /**
     * 解析工具调用的 JSON 参数（使用 Jackson，正确处理 \n \t 等转义）
     */
    static Map<String, String> parseToolArguments(String json) {
        Map<String, String> result = new HashMap<>();
        if (json == null || json.isBlank()) return result;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json.trim());
            if (node.isObject()) {
                var fields = node.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    result.put(entry.getKey(), entry.getValue().asText());
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
