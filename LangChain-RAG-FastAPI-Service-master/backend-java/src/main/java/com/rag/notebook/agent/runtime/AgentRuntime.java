package com.rag.notebook.agent.runtime;

import com.rag.notebook.agent.AgentLoop;
import com.rag.notebook.agent.AgentLoopResult;
import com.rag.notebook.agent.AgentState;
import com.rag.notebook.agent.ContextManager;
import com.rag.notebook.agent.SubTask;
import com.rag.notebook.agent.SupervisorService;
import com.rag.notebook.agent.trace.AgentTraceService;
import com.rag.notebook.chat.entity.ChatMessage;
import com.rag.notebook.chat.service.ChatService;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    private static final List<String> FETCH_ONLY_TOOLS = List.of("fetchUrl");
    private static final List<String> CREATE_NOTE_TOOLS = List.of("createNote");
    private static final List<String> GENERATE_MINDMAP_TOOLS = List.of("generateMindMap");
    private static final List<String> APPEND_NOTE_TOOLS = List.of("appendNote");

    private final AgentTaskService agentTaskService;
    private final AgentEventService agentEventService;
    private final SupervisorService supervisorService;
    private final ChatService chatService;
    private final ContextManager contextManager;
    private final AgentLoop agentLoop;
    private final ReflectionService reflectionService;
    private final ReplanningService replanningService;
    private final AgentTraceService agentTraceService;

    public AgentRuntime(AgentTaskService agentTaskService,
                        AgentEventService agentEventService,
                        SupervisorService supervisorService,
                        ChatService chatService,
                        ContextManager contextManager,
                        AgentLoop agentLoop,
                        ReflectionService reflectionService,
                        ReplanningService replanningService,
                        AgentTraceService agentTraceService) {
        this.agentTaskService = agentTaskService;
        this.agentEventService = agentEventService;
        this.supervisorService = supervisorService;
        this.chatService = chatService;
        this.contextManager = contextManager;
        this.agentLoop = agentLoop;
        this.reflectionService = reflectionService;
        this.replanningService = replanningService;
        this.agentTraceService = agentTraceService;
    }

    public RuntimeResult start(String taskId,
                               String query,
                               String queryWithContext,
                               String sessionId,
                               String userId,
                               String systemPrompt,
                               List<ToolSpecification> activeTools,
                               SseEmitter emitter,
                               boolean enablePlanning,
                               boolean artifactWriteBack,
                               ChatLanguageModel chatModel) throws IOException {

        // 开始 Agent Trace 记录（已在 AgentService 中初始化，这里不重复调用）
        log.info("Agent trace running: taskId={}", taskId);

        AgentTaskStatus runningStatus = AgentTaskStatus.RUNNING;
        agentTaskService.updateStatus(taskId, runningStatus);
        agentEventService.recordAndEmit(taskId, AgentTaskEventType.TASK_CREATED,
                Map.of("task_id", taskId, "session_id", sessionId, "status", runningStatus.name()), emitter);

        List<ChatMessage> history = chatService.getSessionMessages(sessionId);
        List<dev.langchain4j.data.message.ChatMessage> historyMessages = contextManager.buildMessages(history, chatModel);

        List<SubTask> subTasks = Collections.emptyList();
        String executionMode = "SINGLE";
        if (enablePlanning) {
            long planningStart = System.currentTimeMillis();
            try {
                emitThinking(emitter, "planning", "正在分析任务并制定计划");
                agentTaskService.updateStatus(taskId, AgentTaskStatus.PLANNING);
                subTasks = supervisorService.plan(query);
                if (subTasks.size() == 1 && !artifactWriteBack) {
                    subTasks = Collections.emptyList();
                }
                executionMode = subTasks.isEmpty() ? "SINGLE" : subTasks.get(0).getExecutionMode();

                // 记录规划决策到 trace
                if (!subTasks.isEmpty()) {
                    long planningLatency = System.currentTimeMillis() - planningStart;
                    String planJson = formatPlanAsJson(subTasks);
                    agentTraceService.recordPlanning(planJson, planningLatency);
                }
            } catch (Exception e) {
                log.error("规划失败，降级为单步执行", e);
                agentTraceService.recordError("PLANNING_ERROR", e.getMessage(), "supervisorService.plan", null);
            }
        }
        agentTaskService.savePlan(taskId, subTasks, executionMode);
        agentEventService.recordAndEmit(taskId, AgentTaskEventType.PLAN_CREATED,
                Map.of("task_id", taskId, "execution_mode", executionMode, "step_count", subTasks.size()), emitter);

        if (subTasks.isEmpty()) {
            WorkerLoopContext context = new WorkerLoopContext(
                    taskId,
                    null,
                    systemPrompt,
                    queryWithContext,
                    userId,
                    sessionId,
                    new AgentState(queryWithContext),
                    historyMessages,
                    activeTools,
                    emitter,
                    10,
                    20,
                    32000,
                    buildSingleGoal(query),
                    null
            );
            AgentLoopResult loopResult = agentLoop.run(context);
            return new RuntimeResult(loopResult, subTasks);
        }

        AgentState sharedState = new AgentState(queryWithContext);
        AgentLoopResult finalResult = null;
        for (int i = 0; i < subTasks.size(); i++) {
            SubTask task = subTasks.get(i);
            String stepId = task.getId() != null ? task.getId() : "R-" + (i + 1);
            agentTaskService.markStepStatus(taskId, stepId, AgentTaskStatus.RUNNING, null);
            agentEventService.recordAndEmit(taskId, AgentTaskEventType.STEP_STARTED,
                    Map.of("task_id", taskId, "step_id", stepId, "label", task.getLabel()), emitter);
            emitThinking(emitter, "step_start", String.format("开始执行步骤 %d/%d: %s", i + 1, subTasks.size(), task.getLabel()));

            AgentLoopResult stepResult = agentLoop.run(buildStepContext(new StepExecutionContext(
                    taskId,
                    stepId,
                    systemPrompt,
                    queryWithContext,
                    userId,
                    sessionId,
                    historyMessages,
                    activeTools,
                    emitter,
                    task.getGoal(),
                    task.getSuccessCriteria(),
                    allowedToolsForTask(task),
                    classifyTask(task),
                    false
            ), sharedState));
            mergeState(sharedState, stepResult.state());
            agentTaskService.markStepStatus(taskId, stepId,
                    mapOutcome(stepResult.outcome()),
                    stepResult.clarificationQuestion() != null ? stepResult.clarificationQuestion() : stepResult.outcome().name());
            finalResult = stepResult;
            if (stepResult.outcome() == AgentLoopResult.Outcome.NEED_CLARIFICATION) {
                agentTaskService.updateStatus(taskId, AgentTaskStatus.WAITING_USER);
                return new RuntimeResult(new AgentLoopResult(stepResult.outcome(), sharedState, stepResult.clarificationQuestion()), subTasks);
            }
        }

        if (finalResult == null) {
            finalResult = AgentLoopResult.ready(sharedState);
        } else {
            finalResult = new AgentLoopResult(finalResult.outcome(), sharedState, finalResult.clarificationQuestion());
        }
        return new RuntimeResult(finalResult, subTasks);
    }

    public RuntimeResult resume(AgentResumeContext resumeContext,
                                String systemPrompt,
                                List<ToolSpecification> activeTools,
                                SseEmitter emitter,
                                ChatLanguageModel chatModel) throws IOException {
        List<ChatMessage> history = chatService.getSessionMessages(resumeContext.sessionId());
        List<dev.langchain4j.data.message.ChatMessage> historyMessages = contextManager.buildMessages(history, chatModel);
        String resumedQuery = resumeContext.resumedSummary() != null && !resumeContext.resumedSummary().isBlank()
                ? resumeContext.resumedQuery() + "\n\n[当前任务摘要]\n" + resumeContext.resumedSummary()
                : resumeContext.resumedQuery();

        if (resumeContext.remainingSteps() == null || resumeContext.remainingSteps().isEmpty()) {
            WorkerLoopContext context = new WorkerLoopContext(
                    resumeContext.taskId(),
                    null,
                    systemPrompt,
                    resumedQuery,
                    resumeContext.userId(),
                    resumeContext.sessionId(),
                    new AgentState(resumedQuery),
                    historyMessages,
                    activeTools,
                    emitter,
                    10,
                    20,
                    32000,
                    buildSingleGoal(resumeContext.resumedQuery()),
                    null
            );
            AgentLoopResult loopResult = agentLoop.run(context);
            return new RuntimeResult(loopResult, List.of());
        }

        AgentState sharedState = new AgentState(resumedQuery);
        AgentLoopResult finalResult = null;
        for (SubTask task : resumeContext.remainingSteps()) {
            String stepId = task.getId();
            String taskType = classifyTask(task);

            // 前置依赖检查：如果当前步骤需要前一步的产物，但前一步失败了，跳过后续步骤
            if (shouldSkipDueToMissingDependency(taskType, sharedState)) {
                log.info("步骤 {} 跳过：前置依赖不满足（类型: ）", stepId, taskType);
                agentTaskService.markStepStatus(resumeContext.taskId(), stepId, AgentTaskStatus.BLOCKED, "前置步骤未完成，缺少必需的数据");
                agentEventService.recordAndEmit(resumeContext.taskId(), AgentTaskEventType.STEP_SKIPPED,
                        Map.of("task_id", resumeContext.taskId(), "step_id", stepId, "reason", "missing_dependency"), emitter);
                continue;
            }

            agentTaskService.markStepStatus(resumeContext.taskId(), stepId, AgentTaskStatus.RUNNING, null);
            agentEventService.recordAndEmit(resumeContext.taskId(), AgentTaskEventType.STEP_STARTED,
                    Map.of("task_id", resumeContext.taskId(), "step_id", stepId, "label", task.getLabel(), "resumed", true), emitter);

            AgentLoopResult stepResult = agentLoop.run(buildStepContext(new StepExecutionContext(
                    resumeContext.taskId(),
                    stepId,
                    systemPrompt,
                    resumedQuery,
                    resumeContext.userId(),
                    resumeContext.sessionId(),
                    historyMessages,
                    activeTools,
                    emitter,
                    task.getGoal(),
                    task.getSuccessCriteria(),
                    allowedToolsForTask(task),
                    taskType,
                    false
            ), sharedState));
            mergeState(sharedState, stepResult.state());

            // 关键步骤失败后触发重规划
            if (shouldTriggerReplan(stepResult, taskType, sharedState)) {
                log.info("步骤 {} 失败且为关键前置步骤，触发重规划", stepId);
                agentEventService.recordAndEmit(resumeContext.taskId(), AgentTaskEventType.REFLECTION_CREATED,
                        Map.of("task_id", resumeContext.taskId(), "step_id", stepId, "reason", "critical_step_failed"), emitter);

                ReflectionResult reflection = reflectionService.reflect(
                        buildStepContext(new StepExecutionContext(
                                resumeContext.taskId(), stepId, systemPrompt, resumedQuery,
                                resumeContext.userId(), resumeContext.sessionId(),
                                historyMessages, activeTools, emitter,
                                task.getGoal(), task.getSuccessCriteria(),
                                allowedToolsForTask(task), taskType, false
                        ), sharedState),
                        null,
                        stepResult.state().buildStateSummary()
                );

                // 记录反思结果到 trace
                if (reflection != null) {
                    agentTraceService.recordReflection(
                            reflection.summary(),
                            reflection.recommendedActions() != null ? reflection.recommendedActions() : List.of(),
                            Map.of("rootCause", reflection.rootCause() != null ? reflection.rootCause() : "",
                                   "shouldReplan", reflection.shouldReplan(),
                                   "confidence", reflection.confidence() != null ? reflection.confidence() : 0.0)
                    );
                }

                if (reflection.shouldReplan()) {
                    agentEventService.recordAndEmit(resumeContext.taskId(), AgentTaskEventType.REPLAN_CREATED,
                            Map.of("task_id", resumeContext.taskId(), "reason", reflection.rootCause()), emitter);

                    // 记录重规划到 trace
                    agentTraceService.recordReplanning(
                            reflection.rootCause(),
                            "关键步骤失败，需要用户提供备用方案"
                    );

                    // 当前简化处理：关键步骤失败后直接终止任务，不再执行后续步骤
                    // TODO: 后续可以调用 SupervisorService 重新规划，生成"请用户提供正文"的备用方案
                    agentTaskService.markStepStatus(resumeContext.taskId(), stepId, AgentTaskStatus.FAILED, reflection.rootCause());
                    agentTaskService.updateStatus(resumeContext.taskId(), AgentTaskStatus.BLOCKED);

                    String clarification = "抓取网页内容失败，无法完成后续步骤。" +
                            "如果你方便的话，可以直接提供文章正文，或者提供其他可访问的链接。";
                    return new RuntimeResult(
                            new AgentLoopResult(AgentLoopResult.Outcome.NEED_CLARIFICATION, sharedState, clarification),
                            Collections.emptyList()
                    );
                }
            }

            agentTaskService.markStepStatus(resumeContext.taskId(), stepId,
                    mapOutcome(stepResult.outcome()),
                    stepResult.clarificationQuestion() != null ? stepResult.clarificationQuestion() : stepResult.outcome().name());
            finalResult = stepResult;
            if (stepResult.outcome() == AgentLoopResult.Outcome.NEED_CLARIFICATION) {
                agentTaskService.updateStatus(resumeContext.taskId(), AgentTaskStatus.WAITING_USER);
                return new RuntimeResult(new AgentLoopResult(stepResult.outcome(), sharedState, stepResult.clarificationQuestion()), resumeContext.remainingSteps());
            }
        }

        if (finalResult == null) {
            finalResult = AgentLoopResult.ready(sharedState);
        } else {
            finalResult = new AgentLoopResult(finalResult.outcome(), sharedState, finalResult.clarificationQuestion());
        }
        return new RuntimeResult(finalResult, resumeContext.remainingSteps());
    }

    private WorkerLoopContext buildStepContext(StepExecutionContext stepContext, AgentState sharedState) {
        AgentState state = sharedState;
        state.setGoal(null);
        state.setSuccessCriteria(List.of());
        state.setToolAllowedNames(stepContext.allowedToolNames());
        state.setCurrentTaskType(stepContext.taskType());
        state.setCurrentStepLabel(resolveStepLabel(stepContext));
        state.setCurrentStepDescription(resolveStepDescription(stepContext));
        state.setCurrentStepInstructions(buildStepInstructions(stepContext, sharedState));
        return new WorkerLoopContext(
                stepContext.taskId(),
                stepContext.stepId(),
                stepContext.systemPrompt(),
                stepContext.userQuery(),
                stepContext.userId(),
                stepContext.sessionId(),
                state,
                stepContext.historyMessages(),
                stepContext.activeTools(),
                stepContext.emitter(),
                10,
                20,
                32000,
                stepContext.goal(),
                stepContext.successCriteria()
        );
    }

    private List<String> allowedToolsForTask(SubTask task) {
        String type = classifyTask(task);
        return switch (type) {
            case "FETCH" -> FETCH_ONLY_TOOLS;
            case "CREATE_NOTE" -> CREATE_NOTE_TOOLS;
            case "GENERATE_MINDMAP" -> GENERATE_MINDMAP_TOOLS;
            case "APPEND_NOTE" -> APPEND_NOTE_TOOLS;
            default -> List.of();
        };
    }

    private String classifyTask(SubTask task) {
        String toolHint = task.getToolHint() != null ? task.getToolHint() : "";

        // 优先看 toolHint，这是 Supervisor 明确给的工具建议
        if ("fetchUrl".equalsIgnoreCase(toolHint)) {
            return "FETCH";
        }
        if ("createNote".equalsIgnoreCase(toolHint)) {
            return "CREATE_NOTE";
        }
        if ("appendNote".equalsIgnoreCase(toolHint)) {
            return "APPEND_NOTE";
        }
        if ("generateMindMap".equalsIgnoreCase(toolHint)) {
            return "GENERATE_MINDMAP";
        }

        // toolHint 为空或无法识别时，才按语义匹配，并优先看"动作词"而非"上下文名词"
        String label = task.getLabel() != null ? task.getLabel() : "";
        String goal = task.getGoal() != null ? task.getGoal() : "";
        String description = task.getDescription() != null ? task.getDescription() : "";

        // 先看明确的写入动作，避免"网址内容"误判
        if (label.contains("新建笔记") || label.contains("创建笔记")
                || goal.contains("创建一篇") || goal.contains("新建一篇")) {
            return "CREATE_NOTE";
        }
        if (label.contains("追加") || label.contains("写入笔记") || label.contains("写回")
                || goal.contains("追加到") || goal.contains("写入新笔记")) {
            return "APPEND_NOTE";
        }
        if (label.contains("思维导图") || label.contains("脑图") || label.contains("生成思维导图")
                || goal.contains("生成思维导图") || goal.contains("生成Mermaid")) {
            return "GENERATE_MINDMAP";
        }

        // 最后才是"抓取/获取网址"，只有明确带动作时才认定
        String allText = (label + "\n" + goal + "\n" + description).toLowerCase();
        if (allText.contains("抓取") || allText.contains("获取网址") || allText.contains("总结网页内容")) {
            return "FETCH";
        }

        return "GENERAL";
    }

    /**
     * 前置依赖检查：判断当前步骤是否应该因前置产物缺失而跳过
     */
    private boolean shouldSkipDueToMissingDependency(String taskType, AgentState sharedState) {
        return switch (taskType) {
            case "CREATE_NOTE" -> {
                // 创建笔记需要正文，如果共享状态里标记了"抓取失败且无内容"，跳过
                boolean fetchFailed = sharedState.isFetchStepFailed();
                String sharedContent = sharedState.getSharedFetchedContent();
                yield fetchFailed && (sharedContent == null || sharedContent.isBlank() || sharedContent.length() < 100);
            }
            case "GENERATE_MINDMAP" -> {
                // 生成导图需要笔记ID或正文
                String noteId = sharedState.getSharedNoteId();
                String content = sharedState.getSharedFetchedContent();
                yield (noteId == null || noteId.isBlank()) && (content == null || content.isBlank() || content.length() < 100);
            }
            case "APPEND_NOTE" -> {
                // 追加内容需要笔记ID
                String noteId = sharedState.getSharedNoteId();
                yield noteId == null || noteId.isBlank();
            }
            default -> false;
        };
    }

    /**
     * 判断是否应该触发重规划：关键前置步骤失败时需要重新评估整个任务
     */
    private boolean shouldTriggerReplan(AgentLoopResult stepResult, String taskType, AgentState sharedState) {
        // 只有在达到最大轮次且是关键前置步骤时才触发
        if (stepResult.outcome() != AgentLoopResult.Outcome.MAX_ROUNDS) {
            return false;
        }
        // FETCH 是关键前置步骤，失败后应该重规划或终止
        if ("FETCH".equals(taskType) && sharedState.isFetchStepFailed()) {
            return true;
        }
        // TODO: 后续可以扩展其他关键步骤的判断
        return false;
    }

    private AgentTaskStatus mapOutcome(AgentLoopResult.Outcome outcome) {
        return switch (outcome) {
            case READY -> AgentTaskStatus.COMPLETED;
            case MAX_ROUNDS -> AgentTaskStatus.BLOCKED;
            case NEED_CLARIFICATION -> AgentTaskStatus.WAITING_USER;
            case BLOCKED -> AgentTaskStatus.BLOCKED;
        };
    }

    private String resolveStepLabel(StepExecutionContext stepContext) {
        return stepContext.stepId() != null ? stepContext.stepId() : stepContext.taskType();
    }

    private String resolveStepDescription(StepExecutionContext stepContext) {
        return stepContext.goal();
    }

    private String buildStepInstructions(StepExecutionContext stepContext, AgentState state) {
        String taskType = stepContext.taskType();
        List<String> allowedTools = stepContext.allowedToolNames();
        StringBuilder sb = new StringBuilder();
        sb.append("你当前在执行一个严格受限的子步骤。\n");
        sb.append("本步骤只允许调用这些工具：").append(String.join(", ", allowedTools)).append("。\n");
        sb.append("任何不在白名单里的工具都禁止调用，禁止自行重建流程、禁止补做其它步骤。\n");
        sb.append("前一步的产物已经保存在共享状态里，优先使用共享状态，不要重新搜索、重新抓取、重新生成。\n");

        switch (taskType) {
            case "FETCH" -> sb.append("当前目标只是在 fetchUrl 中拿到高质量正文。完成后停止，不要创建笔记、不要搜索笔记、不要生成导图。\n");
            case "CREATE_NOTE" -> sb.append("当前目标只是在已有正文基础上创建笔记。正文已经在共享状态中时，直接使用；不要再抓网页，不要查询笔记列表，不要生成导图。\n");
            case "GENERATE_MINDMAP" -> sb.append("当前目标只是在已有笔记上下文中生成导图。优先使用共享 noteId；不要再读取最近笔记，不要抓网页，不要创建或追加笔记。\n");
            case "APPEND_NOTE" -> sb.append("当前目标只是在已有笔记中追加导图或内容。优先使用共享 noteId 和共享导图；不要重新生成导图，不要搜索笔记，不要抓网页。\n");
            default -> sb.append("如果共享状态已经提供了本步骤所需输入，直接完成当前步骤。\n");
        }

        if (state.getSharedNoteId() != null && !state.getSharedNoteId().isBlank()) {
            sb.append("共享 noteId：").append(state.getSharedNoteId()).append("\n");
        }
        if (state.getSharedFetchedContent() != null && !state.getSharedFetchedContent().isBlank()) {
            String preview = state.getSharedFetchedContent().length() > 300
                    ? state.getSharedFetchedContent().substring(0, 300) + "..."
                    : state.getSharedFetchedContent();
            sb.append("共享正文预览：").append(preview).append("\n");
        }
        if (state.getSharedMindMap() != null && !state.getSharedMindMap().isBlank()) {
            String preview = state.getSharedMindMap().length() > 300
                    ? state.getSharedMindMap().substring(0, 300) + "..."
                    : state.getSharedMindMap();
            sb.append("共享导图预览：").append(preview).append("\n");
        }
        return sb.toString();
    }

    private void mergeState(AgentState target, AgentState source) {
        if (target == source) {
            return;
        }
        target.getToolHistory().addAll(source.getToolHistory());
        source.getObservations().forEach(target::addObservation);
        source.getWorkingMemory().forEach(target::addKnownFact);
        source.getArtifacts().forEach(target::addArtifact);
        target.upgradeEvidence(source.getHighestEvidence());
        if (source.hasWriteConfirmation()) {
            target.markWriteConfirmation(source.getWriteConfirmation());
        }
        target.setSharedNoteId(source.getSharedNoteId() != null ? source.getSharedNoteId() : target.getSharedNoteId());
        target.setSharedNoteTitle(source.getSharedNoteTitle() != null ? source.getSharedNoteTitle() : target.getSharedNoteTitle());
        target.setSharedFetchedContent(source.getSharedFetchedContent() != null ? source.getSharedFetchedContent() : target.getSharedFetchedContent());
        target.setSharedMindMap(source.getSharedMindMap() != null ? source.getSharedMindMap() : target.getSharedMindMap());
    }

    private void emitThinking(SseEmitter emitter, String stage, String content) {
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name("thinking")
                    .data(Map.of("type", "thinking", "stage", stage, "content", content)));
        } catch (IOException e) {
            log.warn("发送思考过程事件失败: stage={}, error={}", stage, e.getMessage());
        }
    }

    private String buildSingleGoal(String query) {
        if (query == null || query.isBlank()) {
            return "回答用户当前问题";
        }
        return "完成用户请求：" + query.trim();
    }

    /**
     * 格式化规划为 JSON 字符串用于 trace 记录
     */
    private String formatPlanAsJson(List<SubTask> subTasks) {
        if (subTasks == null || subTasks.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < subTasks.size(); i++) {
            SubTask task = subTasks.get(i);
            if (i > 0) sb.append(",");
            sb.append("{");
            sb.append("\"id\":\"").append(task.getId() != null ? task.getId() : "").append("\",");
            sb.append("\"label\":\"").append(escapeJson(task.getLabel())).append("\",");
            sb.append("\"goal\":\"").append(escapeJson(task.getGoal())).append("\",");
            sb.append("\"toolHint\":\"").append(task.getToolHint() != null ? task.getToolHint() : "").append("\"");
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    public record RuntimeResult(AgentLoopResult loopResult, List<SubTask> subTasks) {}
}
