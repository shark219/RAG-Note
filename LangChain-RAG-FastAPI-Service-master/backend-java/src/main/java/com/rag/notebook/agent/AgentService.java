package com.rag.notebook.agent;

import com.rag.notebook.agent.runtime.AgentResumeContext;
import com.rag.notebook.agent.runtime.AgentRuntime;
import com.rag.notebook.agent.runtime.AgentTaskService;
import com.rag.notebook.agent.runtime.AgentTaskStatus;
import com.rag.notebook.chat.entity.ChatMessage;
import com.rag.notebook.chat.service.ChatService;
import com.rag.notebook.config.ApplicationProperties;
import com.rag.notebook.evaluation.entity.RagTrace;
import com.rag.notebook.evaluation.repository.RagTraceRepository;
import com.rag.notebook.rag.QualityReviewer;
import com.rag.notebook.skill.service.SkillContextResolver;
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
    private final AgentRuntime agentRuntime;
    private final AgentTaskService agentTaskService;
    private final List<ToolSpecification> toolSpecifications;
    private final SkillContextResolver skillContextResolver;

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
                        ConversationContextManager ctxManager,
                        AgentRuntime agentRuntime,
                        AgentTaskService agentTaskService,
                        SkillContextResolver skillContextResolver) {
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
        this.agentRuntime = agentRuntime;
        this.agentTaskService = agentTaskService;
        this.skillContextResolver = skillContextResolver;
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
    private List<ToolSpecification> filterTools(boolean enableKnowledge, boolean enableNotes,
                                                SkillContextResolver.Context skillContext) {
        return toolSpecifications.stream()
                .filter(tool -> {
                    String name = tool.name();
                    if ("ragSummary".equals(name)) return enableKnowledge || enableNotes;
                    if (KNOWLEDGE_TOOLS.contains(name)) return enableKnowledge;
                    if (NOTE_TOOLS.contains(name)) return enableNotes;
                    return true;
                })
                .filter(tool -> !skillContext.restricted() || skillContext.allowedTools().contains(tool.name()))
                .toList();
    }

    public SseEmitter resumeAgentTask(AgentResumeContext resumeContext, String userMessage) {
        SseEmitter emitter = new SseEmitter(300000L);
        SecurityContext securityContext = SecurityContextHolder.getContext();
        String traceId = UUID.randomUUID().toString().replace("-", "");

        CompletableFuture.runAsync(() -> {
            SecurityContextHolder.setContext(securityContext);
            long startTime = System.currentTimeMillis();
            try {
                SkillContextResolver.Context skillContext = skillContextResolver.resolve(resumeContext.userId());
                List<ToolSpecification> activeTools = filterTools(true, true, skillContext);
                String systemPrompt = loadSystemPrompt() + skillContext.prompt();
                ChatLanguageModel chatModel = modelFactory.createBalancedModel();

                AgentRuntime.RuntimeResult runtimeResult = agentRuntime.resume(
                        resumeContext,
                        systemPrompt,
                        activeTools,
                        emitter,
                        chatModel
                );

                AgentLoopResult loopResult = runtimeResult.loopResult();
                AgentState finalAgentState = loopResult.state();
                String response;
                if (loopResult.outcome() == AgentLoopResult.Outcome.NEED_CLARIFICATION) {
                    response = loopResult.clarificationQuestion();
                    agentTaskService.updateStatus(resumeContext.taskId(), AgentTaskStatus.WAITING_USER);
                } else {
                    response = composeAndReview(loopResult, resumeContext.resumedQuery(), emitter);
                    agentTaskService.saveFinalResult(resumeContext.taskId(), AgentTaskStatus.COMPLETED, response, null);
                }

                if (userMessage != null && !userMessage.isBlank()) {
                    chatService.addMessage(resumeContext.sessionId(), resumeContext.userId(), "human", userMessage);
                }
                chatService.addMessage(resumeContext.sessionId(), resumeContext.userId(), "ai", response);

                sendSseEvent(emitter, "thinking", Map.of(
                        "stage", "complete",
                        "content", "恢复执行完成"
                ));

                int chunkSize = 50;
                for (int i = 0; i < response.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, response.length());
                    sendSseEvent(emitter, "response", Map.of(
                            "content", response.substring(i, end),
                            "session_id", resumeContext.sessionId()
                    ));
                    Thread.sleep(50);
                }

                String ragTraceId = agentTools.getLatestTraceId();
                String finalTraceId = ragTraceId != null ? ragTraceId : traceId;
                List<ChatMessage> allHistory = chatService.getSessionMessages(resumeContext.sessionId());
                int usedTokens = tokenCounter.estimateEntityTokens(allHistory);

                Map<String, Object> doneData = new HashMap<>();
                doneData.put("session_id", resumeContext.sessionId());
                doneData.put("task_id", resumeContext.taskId());
                doneData.put("trace_id", finalTraceId);
                doneData.put("token_used", usedTokens);
                doneData.put("token_max", 32000);

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

                if (ragTraceId == null) {
                    try {
                        RagTrace basicTrace = new RagTrace();
                        basicTrace.setTraceId(traceId);
                        basicTrace.setUserId(resumeContext.userId());
                        basicTrace.setQuery(resumeContext.resumedQuery());
                        basicTrace.setFinalAnswer(response);
                        basicTrace.setTotalLatencyMs(System.currentTimeMillis() - startTime);
                        traceRepository.save(basicTrace);
                    } catch (Exception ex) {
                        log.warn("Failed to save basic trace: {}", ex.getMessage());
                    }
                }

                safeComplete(emitter);
            } catch (Exception e) {
                log.error("Agent resume failed: {}", e.getMessage(), e);
                try {
                    chatService.addMessage(resumeContext.sessionId(), resumeContext.userId(), "ai", "恢复任务时发生错误: " + e.getMessage());
                } catch (Exception ignored) {}
                try {
                    sendSseEvent(emitter, "error", Map.of(
                            "content", "恢复任务时发生错误: " + e.getMessage(),
                            "session_id", resumeContext.sessionId(),
                            "task_id", resumeContext.taskId()
                    ));
                    safeComplete(emitter);
                } catch (IOException ex) {
                    try {
                        emitter.completeWithError(ex);
                    } catch (IllegalStateException ignored) {}
                }
            } finally {
                agentTools.clearSearchFilters();
                SecurityContextHolder.clearContext();
            }
        }, taskExecutor);

        return emitter;
    }

    public SseEmitter streamAgentResponse(String query, String sessionId, String userId) {
        return streamAgentResponse(query, sessionId, userId, false, true, true, null, null, null);
    }

    public SseEmitter streamAgentResponse(String query, String sessionId, String userId,
                                          boolean regenerate,
                                          boolean enableKnowledge, boolean enableNotes,
                                          List<String> fileIds) {
        return streamAgentResponse(query, sessionId, userId, regenerate,
                enableKnowledge, enableNotes, null, null, fileIds);
    }

    public SseEmitter streamAgentResponse(String query, String sessionId, String userId,
                                          boolean regenerate,
                                          boolean enableKnowledge, boolean enableNotes,
                                          List<String> selectedKnowledgeDocs, List<String> selectedNotes,
                                          List<String> fileIds) {
        SseEmitter emitter = new SseEmitter(300000L);

        SecurityContext securityContext = SecurityContextHolder.getContext();
        String traceId = UUID.randomUUID().toString().replace("-", "");

        CompletableFuture.runAsync(() -> {
            SecurityContextHolder.setContext(securityContext);
            long startTime = System.currentTimeMillis();

            agentTools.setSearchFilters(enableKnowledge, enableNotes,
                    selectedKnowledgeDocs, selectedNotes);
            try {
                SkillContextResolver.Context skillContext = skillContextResolver.resolve(userId);
                List<ToolSpecification> activeTools = filterTools(enableKnowledge, enableNotes, skillContext);
                if (!regenerate) {
                    chatService.addMessage(sessionId, userId, "human", query);
                }

                log.info("工具过滤: enableKnowledge={}, enableNotes={}, skills={}, selectedKnowledgeDocs={}, selectedNotes={}, 可用工具数={}, tools={}",
                        enableKnowledge, enableNotes, skillContext.version(), selectedKnowledgeDocs, selectedNotes,
                        activeTools.size(), activeTools.stream().map(ToolSpecification::name).toList());

                String attachmentContext = chatService.buildAttachmentContext(fileIds, userId);
                String queryWithContext = attachmentContext != null
                        ? attachmentContext + "用户问题：" + query
                        : query;
                String resolvedQuery = convCtxManager.resolveReferences(queryWithContext, sessionId);
                String systemPrompt = loadSystemPrompt() + skillContext.prompt();

                boolean useSupervisor = shouldUseSupervisor(query);
                boolean artifactWriteBack = isArtifactWriteBackTask(query.trim().toLowerCase(Locale.ROOT));

                ChatLanguageModel chatModel = modelFactory.createBalancedModel();
                String normalizedGoal = buildSingleGoal(query);
                var task = agentTaskService.createTask(userId, sessionId, query, normalizedGoal,
                        useSupervisor ? "PLANNED" : "SINGLE");

                AgentRuntime.RuntimeResult runtimeResult = agentRuntime.start(
                        task.getTaskId(),
                        query,
                        resolvedQuery,
                        sessionId,
                        userId,
                        systemPrompt,
                        activeTools,
                        emitter,
                        useSupervisor,
                        artifactWriteBack,
                        chatModel
                );

                AgentLoopResult loopResult = runtimeResult.loopResult();
                AgentState finalAgentState = loopResult.state();
                String response;
                if (loopResult.outcome() == AgentLoopResult.Outcome.NEED_CLARIFICATION) {
                    response = loopResult.clarificationQuestion();
                    agentTaskService.updateStatus(task.getTaskId(), AgentTaskStatus.WAITING_USER);
                } else {
                    response = composeAndReview(loopResult, query, emitter);
                    agentTaskService.saveFinalResult(task.getTaskId(), AgentTaskStatus.COMPLETED, response, null);
                }

                chatService.addMessage(sessionId, userId, "ai", response);

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

                String ragTraceId = agentTools.getLatestTraceId();
                String finalTraceId = ragTraceId != null ? ragTraceId : traceId;
                log.info("Agent done - ragTraceId={}, finalTraceId={}, sessionId={}", ragTraceId, finalTraceId, sessionId);

                List<ChatMessage> allHistory = chatService.getSessionMessages(sessionId);
                int usedTokens = tokenCounter.estimateEntityTokens(allHistory);
                int maxTokens = 32000;

                Map<String, Object> doneData = new HashMap<>();
                doneData.put("session_id", sessionId);
                doneData.put("task_id", task.getTaskId());
                doneData.put("trace_id", finalTraceId);
                doneData.put("token_used", usedTokens);
                doneData.put("token_max", maxTokens);

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
                agentTools.clearSearchFilters();
                SecurityContextHolder.clearContext();
            }
        }, taskExecutor);

        return emitter;
    }

    // ========== 统一管道 ==========

    /** 管道执行结果：回答 + Agent 状态（含产物数据） */
    private record PipeResult(String response, AgentState state) {}

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

        int knowledgeEvidenceCount = pack.knowledgeBaseSummary() != null && !pack.knowledgeBaseSummary().isBlank() ? 1 : 0;
        log.info("Composer 完成: outcome={}, 证据笔记 {} 篇, 知识库证据 {} 条, 产物 {} 个, 回答 {} 字",
                loopResult.outcome(), pack.notes().size(), knowledgeEvidenceCount,
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

        // 传播关键失败状态
        if (source.isFetchStepFailed()) {
            log.info("mergeState: 传播 fetchStepFailed=true 到目标状态");
            target.setFetchStepFailed(true);
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

    /**
     * 解析工具调用的 JSON 参数（使用 Jackson，正确处理 \n \t 等转义）
     */
    public static Map<String, String> parseToolArguments(String json) {
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
