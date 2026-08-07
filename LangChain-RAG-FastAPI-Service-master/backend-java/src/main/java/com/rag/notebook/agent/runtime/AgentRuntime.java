package com.rag.notebook.agent.runtime;

import com.rag.notebook.agent.AgentLoop;
import com.rag.notebook.agent.AgentLoopResult;
import com.rag.notebook.agent.AgentState;
import com.rag.notebook.agent.ContextManager;
import com.rag.notebook.agent.SubTask;
import com.rag.notebook.agent.SupervisorService;
import com.rag.notebook.chat.entity.ChatMessage;
import com.rag.notebook.chat.service.ChatService;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class AgentRuntime {

    private static final List<String> FETCH_ONLY_TOOLS = List.of("fetchUrl");
    private static final List<String> CREATE_NOTE_TOOLS = List.of("createNote", "fetchUrl", "whatTimeIsNow");
    private static final List<String> GENERATE_MINDMAP_TOOLS = List.of("generateMindMap", "fetchUrl", "getRecentNotes", "getNote", "whatTimeIsNow");
    private static final List<String> APPEND_NOTE_TOOLS = List.of("appendNote", "getRecentNotes", "getNote", "whatTimeIsNow");

    private final AgentTaskService agentTaskService;
    private final AgentEventService agentEventService;
    private final SupervisorService supervisorService;
    private final ChatService chatService;
    private final ContextManager contextManager;
    private final AgentLoop agentLoop;

    public AgentRuntime(AgentTaskService agentTaskService,
                        AgentEventService agentEventService,
                        SupervisorService supervisorService,
                        ChatService chatService,
                        ContextManager contextManager,
                        AgentLoop agentLoop) {
        this.agentTaskService = agentTaskService;
        this.agentEventService = agentEventService;
        this.supervisorService = supervisorService;
        this.chatService = chatService;
        this.contextManager = contextManager;
        this.agentLoop = agentLoop;
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
        AgentTaskStatus runningStatus = AgentTaskStatus.RUNNING;
        agentTaskService.updateStatus(taskId, runningStatus);
        agentEventService.recordAndEmit(taskId, AgentTaskEventType.TASK_CREATED,
                Map.of("task_id", taskId, "session_id", sessionId, "status", runningStatus.name()), emitter);

        List<ChatMessage> history = chatService.getSessionMessages(sessionId);
        List<dev.langchain4j.data.message.ChatMessage> historyMessages = contextManager.buildMessages(history, chatModel);

        List<SubTask> subTasks = enablePlanning ? supervisorService.plan(query) : Collections.emptyList();
        if (subTasks.size() == 1 && !artifactWriteBack) {
            subTasks = Collections.emptyList();
        }
        String executionMode = subTasks.isEmpty() ? "SINGLE" : subTasks.get(0).getExecutionMode();
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
                    classifyTask(task),
                    false
            ), sharedState));
            mergeState(sharedState, stepResult.state());
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
        String text = ((task.getLabel() != null ? task.getLabel() : "") + "\n"
                + (task.getGoal() != null ? task.getGoal() : "") + "\n"
                + (task.getDescription() != null ? task.getDescription() : "")).toLowerCase();
        if (text.contains("抓取") || text.contains("网址") || text.contains("网页")) return "FETCH";
        if (text.contains("新建笔记") || text.contains("创建笔记")) return "CREATE_NOTE";
        if (text.contains("思维导图") || text.contains("脑图")) return "GENERATE_MINDMAP";
        if (text.contains("追加") || text.contains("写入") || text.contains("写回")) return "APPEND_NOTE";
        return "GENERAL";
    }

    private AgentTaskStatus mapOutcome(AgentLoopResult.Outcome outcome) {
        return switch (outcome) {
            case READY -> AgentTaskStatus.COMPLETED;
            case MAX_ROUNDS -> AgentTaskStatus.BLOCKED;
            case NEED_CLARIFICATION -> AgentTaskStatus.WAITING_USER;
        };
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

    private String buildSingleGoal(String query) {
        if (query == null || query.isBlank()) {
            return "回答用户当前问题";
        }
        return "完成用户请求：" + query.trim();
    }

    public record RuntimeResult(AgentLoopResult loopResult, List<SubTask> subTasks) {}
}
