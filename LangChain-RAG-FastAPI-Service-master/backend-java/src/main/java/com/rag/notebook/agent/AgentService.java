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

                // 所有请求先交给 Supervisor 做语义分诊；简单任务返回空数组后降级为单 Agent。
                List<SubTask> subTasks = supervisorService.plan(query);
                if (subTasks.size() == 1 && !isArtifactWriteBackTask(query.trim().toLowerCase(Locale.ROOT))) {
                    log.info("Supervisor 返回单一子任务，降级为单 Agent 执行: {}", subTasks.get(0).getGoal());
                    subTasks = Collections.emptyList();
                }
                if (subTasks.isEmpty()) {
                    log.info("Supervisor 判断为简单任务或规划降级：使用单 Agent 执行");
                }

                String response;
                AgentState finalAgentState = null;
                String systemPrompt = loadSystemPrompt();

                if (subTasks.isEmpty()) {
                    String resolvedQuery = convCtxManager.resolveReferences(queryWithContext, sessionId);

                    AgentLoopResult loopResult = agentLoop.run(systemPrompt, resolvedQuery,
                            historyMessages, userId, sessionId, activeTools, emitter,
                            buildSingleGoal(query), null);
                    finalAgentState = loopResult.state();

                    // 反问澄清：直接输出反问，跳过 Compose/Review
                    if (loopResult.outcome() == AgentLoopResult.Outcome.NEED_CLARIFICATION) {
                        response = loopResult.clarificationQuestion();
                    } else {
                        response = composeAndReview(loopResult, query, emitter);
                    }
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
     * 顺序执行管道：按子任务逐个运行 AgentLoop，并把上一步结果注入下一步。
     */
    private PipeResult runSequentialPipeline(String systemPrompt, String query,
                                          List<dev.langchain4j.data.message.ChatMessage> historyMessages,
                                          String userId, String sessionId,
                                          List<ToolSpecification> activeTools,
                                          SseEmitter emitter,
                                          List<SubTask> subTasks) throws IOException {
        String resolvedQuery = convCtxManager.resolveReferences(query, sessionId);

        sendSseEvent(emitter, "thinking", Map.of(
                "stage", "planning",
                "content", "顺序执行 " + subTasks.size() + " 个目标"
        ));

        record SubResult(String taskId, String label, String content, AgentState state) {}
        List<SubResult> results = new ArrayList<>();
        AgentState mergedState = new AgentState(query);
        StringBuilder completedContext = new StringBuilder();

        for (int i = 0; i < subTasks.size(); i++) {
            SubTask task = subTasks.get(i);
            sendSseEvent(emitter, "thinking", Map.of(
                    "stage", "planning",
                    "content", "顺序执行第 " + (i + 1) + "/" + subTasks.size() + " 个目标: " + task.getLabel()
            ));

            String taskPrompt = buildSequentialTaskPrompt(systemPrompt, task, i, subTasks.size());
            String taskQuery = buildSequentialTaskQuery(completedContext, task);

            AgentLoopResult loopResult = agentLoop.run(taskPrompt, taskQuery,
                    historyMessages, userId, sessionId, activeTools, emitter,
                    task.getGoal(), task.getSuccessCriteria());

            mergeState(mergedState, loopResult.state());

            if (loopResult.outcome() == AgentLoopResult.Outcome.NEED_CLARIFICATION) {
                return new PipeResult(loopResult.clarificationQuestion(), mergedState);
            }

            String content = responseComposer.compose(EvidencePack.from(loopResult.state()), loopResult.outcome());
            results.add(new SubResult(task.getId(), task.getLabel(), content, loopResult.state()));

            completedContext.append(buildSubTaskContextEntry(task, content, loopResult.state()));
        }

        sendSseEvent(emitter, "thinking", Map.of(
                "stage", "composing",
                "content", "正在整合 " + results.size() + " 个顺序目标结果"
        ));

        String answer = synthesizeResults(query, subTasks, results);
        return new PipeResult(answer, mergedState);
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
                            + "\n请专注于完成此目标，完成后直接输出结果。";

                    AgentLoopResult r = agentLoop.run(taskPrompt, query,
                            historyMessages, userId, sessionId, activeTools, emitter,
                            task.getGoal(), null);

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

        // 质量审查：传入所有可用证据（笔记内容 + 操作结果 + 知识库摘要）
        List<Map<String, Object>> reviewDocs = new ArrayList<>();
        for (var note : pack.notes()) {
            reviewDocs.add(Map.<String, Object>of("content", buildReviewEvidence(note)));
        }
        if (pack.writeConfirmation() != null) {
            reviewDocs.add(Map.<String, Object>of("content", "[操作结果] " + pack.writeConfirmation()));
        }
        if (pack.knowledgeBaseSummary() != null) {
            reviewDocs.add(Map.<String, Object>of("content", "[知识库] " + pack.knowledgeBaseSummary()));
        }
        var review = qualityReviewer.reviewAnswer(query, reviewDocs, answer);

        if (!review.approved()) {
            log.info("质量审查未通过: {}, 重新生成", review.reason());
            answer = responseComposer.compose(pack, loopResult.outcome());
        }

        return answer;
    }

    private String buildReviewEvidence(EvidencePack.NoteEvidence note) {
        StringBuilder sb = new StringBuilder();
        if (note.title() != null && !note.title().isBlank()) {
            sb.append("标题：").append(note.title()).append("\n");
        }
        if (note.noteId() != null && !note.noteId().isBlank()) {
            sb.append("笔记ID：").append(note.noteId()).append("\n");
        }
        if (note.category() != null && !note.category().isBlank()) {
            sb.append("分类：").append(note.category()).append("\n");
        }
        if (note.tags() != null && !note.tags().isBlank()) {
            sb.append("标签：").append(note.tags()).append("\n");
        }
        sb.append("证据深度：").append(note.depth()).append("\n");
        if (note.content() != null && !note.content().isBlank()) {
            String content = note.content();
            sb.append("内容：")
                    .append(content.substring(0, Math.min(700, content.length())))
                    .append("\n");
        }
        return sb.toString();
    }

    /**
     * 合并多个 AgentState 到一个
     */
    private void mergeState(AgentState target, AgentState source) {
        target.getToolHistory().addAll(source.getToolHistory());
        for (var observation : source.getObservations()) {
            target.addObservation(observation);
        }
        for (var fact : source.getWorkingMemory()) {
            target.addKnownFact(fact);
        }
        for (var artifact : source.getArtifacts()) {
            target.addArtifact(artifact);
        }
        target.upgradeEvidence(source.getHighestEvidence());
        if (source.hasWriteConfirmation()) {
            target.markWriteConfirmation(source.getWriteConfirmation());
        }
    }

    /**
     * 轻量路由：只有明显复合任务才交给 Supervisor 拆分。
     * 单一笔记任务（搜索、读取、列表、今日复习等）让 AgentLoop 自己完成，减少延迟和规划漂移。
     */
    private boolean shouldUseSupervisor(String query) {
        if (query == null || query.isBlank()) return false;

        String q = query.trim().toLowerCase(Locale.ROOT);

        if (isArtifactWriteBackTask(q)) {
            return true;
        }

        // "搜索并读取/查找并打开"是单一信息获取链路，AgentLoop 一次循环更稳。
        String[] singleFlowPatterns = {
                "搜索并读取", "查找并读取", "找到并读取", "找一下并读取",
                "搜索并打开", "查找并打开", "找到并打开",
                "搜索并看看", "查找并看看", "找到并看看"
        };
        for (String pattern : singleFlowPatterns) {
            if (q.contains(pattern)) return false;
        }

        String[] sequenceMarkers = {"然后", "接着", "之后", "随后", "最后", "顺便", "再给", "再帮", "再生成"};
        for (String marker : sequenceMarkers) {
            if (q.contains(marker)) return true;
        }

        String[] parallelMarkers = {"同时", "以及", "并且"};
        for (String marker : parallelMarkers) {
            if (q.contains(marker)) return true;
        }

        return java.util.regex.Pattern.compile("并(总结|整理|创建|生成|写|追加|合并|对比|比较|制定|安排|删除|编辑|分析)")
                .matcher(q)
                .find();
    }

    private boolean isArtifactWriteBackTask(String q) {
        boolean wantsArtifact = containsAny(q, "思维导图", "导图", "脑图", "mindmap", "图表", "结构图");
        boolean wantsWriteBack = containsAny(q, "写进", "写入", "写回", "追加", "保存到", "放进",
                "贴到", "加到", "加上", "加入", "附到", "插入", "更新到");
        boolean noteTarget = containsAny(q, "原来笔记", "原来的笔记", "原笔记", "当前笔记", "这篇笔记",
                "笔记里", "笔记中", "笔记里面", "原文", "原来内容");
        return wantsArtifact && wantsWriteBack && noteTarget;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private String buildSingleGoal(String query) {
        if (query == null || query.isBlank()) {
            return "回答用户当前问题";
        }
        return "完成用户请求：" + query.trim();
    }

    private String buildSequentialTaskPrompt(String systemPrompt, SubTask task, int index, int total) {
        StringBuilder sb = new StringBuilder(systemPrompt);
        sb.append("\n\n[顺序任务执行]\n");
        sb.append("当前是第 ").append(index + 1).append("/").append(total).append(" 个子任务。\n");
        sb.append("当前目标：").append(task.getGoal()).append("\n");
        if (task.getToolHint() != null && !task.getToolHint().isBlank()) {
            sb.append("建议工具：").append(task.getToolHint()).append("（仅作参考，必要时可换工具）\n");
        }
        sb.append("只完成当前目标，不要提前执行后续目标，也不要重复执行已完成的上一步。\n");
        sb.append("如果当前目标是读取笔记，成功读取完整笔记后就停止；不要生成导图或写回笔记。\n");
        sb.append("如果当前目标是生成导图，成功生成导图后就停止；不要写回笔记。\n");
        sb.append("如果当前目标是写回笔记，只把上一步产物追加/写入原笔记一次。当前目标完成后直接输出当前结果。");
        return sb.toString();
    }

    private String buildSequentialTaskQuery(StringBuilder completedContext, SubTask task) {
        StringBuilder sb = new StringBuilder();
        if (completedContext.length() > 0) {
            sb.append("[已完成的上一步结果]\n").append(completedContext).append("\n");
        }
        if (task.getDescription() != null && !task.getDescription().isBlank()) {
            sb.append("当前子任务上下文：").append(task.getDescription()).append("\n");
        }
        sb.append("当前子任务目标：").append(task.getGoal());
        return sb.toString();
    }

    private String buildSubTaskContextEntry(SubTask task, String content, AgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(task.getLabel() != null ? task.getLabel() : task.getId()).append(": ");
        sb.append(content != null ? content : "").append("\n");

        if (!state.getWorkingMemory().isEmpty()) {
            sb.append("  [关键事实]\n");
            for (String fact : state.getWorkingMemory()) {
                sb.append("  - ").append(fact).append("\n");
            }
        }

        for (Artifact artifact : state.getArtifacts()) {
            if (artifact.metadata() == null) continue;
            Object artifactContent = artifact.metadata().get("content");
            if (artifactContent != null && !artifactContent.toString().isBlank()) {
                sb.append("  [产物内容: ").append(artifact.type()).append("]\n");
                sb.append(truncateForContext(artifactContent.toString(), 8000)).append("\n");
            }
        }
        return sb.toString();
    }

    private String truncateForContext(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "\n...(truncated)";
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
