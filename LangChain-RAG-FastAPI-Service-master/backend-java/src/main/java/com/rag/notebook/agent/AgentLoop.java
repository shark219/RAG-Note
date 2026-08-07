package com.rag.notebook.agent;

import com.rag.notebook.agent.AgentState.ResultQuality;
import com.rag.notebook.agent.runtime.AgentEventService;
import com.rag.notebook.agent.runtime.AgentExecutionSnapshot;
import com.rag.notebook.agent.runtime.AgentTaskEventType;
import com.rag.notebook.agent.runtime.AgentTaskService;
import com.rag.notebook.agent.runtime.ReflectionResult;
import com.rag.notebook.agent.runtime.ReflectionService;
import com.rag.notebook.agent.runtime.ReplanResult;
import com.rag.notebook.agent.runtime.ReplanningService;
import com.rag.notebook.agent.runtime.WorkerLoopContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 主循环：目标驱动，Observation 驱动的自主决策。
 *
 * 核心流程：
 *   Goal → Action → Observation → State Update → Goal Check → Done / Continue
 *
 * 与旧版的关键区别：
 *   旧：要求 LLM 调指定工具 → 执行 → CompletionGate 拦截 → ping-pong
 *   新：给 LLM 目标 → 自主选工具 → 执行 → Observation 注入 → GoalEvaluator 判断
 *
 * GoalEvaluator 在每次工具成功后主动评估——如果目标达成，不等 LLM 决定，
 * 直接结束循环返回。这让 Agent 的行为从"严格执行规则的机器人"变成"有目标的助手"。
 */
@Slf4j
@Component
public class AgentLoop {

    private static final int MAX_ITERATIONS = 10;

    private final AgentTools agentTools;
    private final ToolResultEvaluator evaluator;
    private final ModelFactory modelFactory;
    private final GoalEvaluator goalEvaluator;
    private final ConversationContextManager contextManager;
    private final ReflectionService reflectionService;
    private final ReplanningService replanningService;
    private final AgentTaskService agentTaskService;
    private final AgentEventService agentEventService;

    public AgentLoop(AgentTools agentTools, ToolResultEvaluator evaluator,
                     ModelFactory modelFactory, GoalEvaluator goalEvaluator,
                     ConversationContextManager contextManager,
                     ReflectionService reflectionService,
                     ReplanningService replanningService,
                     AgentTaskService agentTaskService,
                     AgentEventService agentEventService) {
        this.agentTools = agentTools;
        this.evaluator = evaluator;
        this.modelFactory = modelFactory;
        this.goalEvaluator = goalEvaluator;
        this.contextManager = contextManager;
        this.reflectionService = reflectionService;
        this.replanningService = replanningService;
        this.agentTaskService = agentTaskService;
        this.agentEventService = agentEventService;
    }

    /**
     * Agent 执行入口。
     *
     * @param goal            任务目标（由 Supervisor 提取），null 表示无明确目标
     * @param successCriteria 成功标准列表，null 表示无明确标准
     */
    public AgentLoopResult run(String systemPrompt, String userQuery,
                      List<ChatMessage> historyMessages,
                      String userId, String sessionId,
                      List<ToolSpecification> activeTools,
                      SseEmitter emitter,
                      String goal, List<String> successCriteria) throws IOException {
        AgentState state = new AgentState(userQuery);
        WorkerLoopContext context = new WorkerLoopContext(
                null,
                null,
                systemPrompt,
                userQuery,
                userId,
                sessionId,
                state,
                historyMessages,
                activeTools,
                emitter,
                MAX_ITERATIONS,
                20,
                32000,
                goal,
                successCriteria
        );
        return run(context);
    }

    public AgentLoopResult run(WorkerLoopContext context) throws IOException {

        AgentState state = context.agentState() != null ? context.agentState() : new AgentState(context.userQuery());
        agentTools.bindState(state);
        state.setTaskId(context.taskId());
        state.setStepId(context.stepId());

        if (context.goal() != null && !context.goal().isBlank()) {
            state.setGoal(context.goal());
            state.setSuccessCriteria(context.successCriteria());
            log.info("目标: goal={}, successCriteria={}", context.goal(), context.successCriteria());
        } else {
            state.setTaskStatus(TaskStatus.EXECUTING);
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(context.systemPrompt()));
        messages.addAll(context.messages());
        messages.add(UserMessage.from(context.userQuery()));

        int maxIterations = context.maxIterations() != null ? context.maxIterations() : MAX_ITERATIONS;
        int maxToolCalls = context.maxToolCalls() != null ? context.maxToolCalls() : 20;

        for (int i = 0; i < maxIterations; i++) {
            state.setIteration(i + 1);
            persistSnapshot(context, state, null, null);
            log.info("Agent Loop 第 {} 轮", i + 1);

            if (state.needsReflection()) {
                ReflectionResult reflection = reflectionService.reflect(context, null, null);
                if (reflection.summary() != null && !reflection.summary().isBlank()) {
                    state.setLastReflectionSummary(reflection.summary());
                    state.setLastFailureType(reflection.failureType());
                    agentEventService.recordAndEmit(context.taskId(), AgentTaskEventType.REFLECTION_CREATED,
                            Map.of("task_id", context.taskId(), "step_id", context.stepId(), "summary", reflection.summary()),
                            context.emitter());
                }
                ReplanResult replanResult = replanningService.replan(context, reflection);
                if (replanResult.summary() != null && !replanResult.summary().isBlank()) {
                    agentEventService.recordAndEmit(context.taskId(), AgentTaskEventType.REPLAN_CREATED,
                            Map.of("task_id", context.taskId(), "step_id", context.stepId(), "summary", replanResult.summary()),
                            context.emitter());
                }
                String stateView = buildStateViewForReflection(state);
                messages.add(UserMessage.from(stateView));
                state.markProgress();
                log.info("注入环境状态视图（连续{}轮无进展）:\n{}",
                        state.getConsecutiveNoProgress() + 1, stateView);
            }

            sendRoundThinking(context.emitter(), i + 1, state);

            ChatLanguageModel llm = modelFactory.createPreciseModel();
            Response<AiMessage> response = llm.generate(messages, context.activeTools());
            AiMessage aiMessage = response.content();

            if (aiMessage.hasToolExecutionRequests()) {
                messages.add(aiMessage);
                boolean anyProgress = false;

                List<String> toolNames = aiMessage.toolExecutionRequests().stream()
                        .map(ToolExecutionRequest::name).toList();
                log.info("LLM 决定调用工具: {} (第{}轮)", toolNames, i + 1);

                int totalTools = aiMessage.toolExecutionRequests().size();
                int toolIndex = 0;
                for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                    if (state.getToolHistory().size() >= maxToolCalls) {
                        state.setBlockedReason("达到最大工具调用次数限制");
                        state.setLastFailureType("MAX_TOOL_CALLS");
                        persistSnapshot(context, state, null, state.getBlockedReason());
                        agentTools.clearBoundState();
                        return AgentLoopResult.maxRounds(state);
                    }
                    toolIndex++;
                    String toolName = req.name();
                    String toolArgs = req.arguments();

                    if (!state.isToolAllowed(toolName)) {
                        String blocked = "当前步骤禁止调用工具: " + toolName;
                        messages.add(ToolExecutionResultMessage.from(req, blocked));
                        state.addFailedAction(toolName + "(" + toolArgs + ")");
                        continue;
                    }

                    agentEventService.recordAndEmit(context.taskId(), AgentTaskEventType.TOOL_CALLED,
                            Map.of("task_id", context.taskId(), "step_id", context.stepId(), "tool", toolName, "args", toolArgs),
                            context.emitter());

                    sendSseEvent(context.emitter(), "thinking", Map.of(
                            "stage", "tool_call",
                            "round", i + 1,
                            "content", "正在调用工具: " + toolName,
                            "tool_index", toolIndex,
                            "tool_total", totalTools
                    ));

                    if (state.hasCalledWithArgsAndFailed(toolName, toolArgs)) {
                        log.info("拦截重复调用: {}({})，之前已失败", toolName, toolArgs);
                        messages.add(ToolExecutionResultMessage.from(req,
                                buildStateAwareBlockMessage(toolName, toolArgs, state)));
                        continue;
                    }

                    if (state.hasSameAction(toolName, toolArgs)) {
                        log.info("拦截机械重复: {}({})，与上一轮完全相同", toolName, toolArgs);
                        messages.add(ToolExecutionResultMessage.from(req,
                                "[环境状态] 该工具和参数刚刚已尝试过，没有推进任务。\n"
                                + state.buildStateSummary() + "\n"
                                + "请分析当前状态并选择不同策略。"));
                        continue;
                    }

                    String rawResult = executeTool(toolName, toolArgs, context.userId(), context.activeTools());
                    ToolResult toolResult = getLastToolResult();

                    ToolResultEvaluator.Evaluation eval = evaluator.evaluate(
                            toolName, toolArgs, rawResult, state.getOriginalQuery(), toolResult);
                    state.recordToolCall(toolName, toolArgs, rawResult, eval.quality());

                    String observationMsg = buildObservationMessage(
                            toolName, toolArgs, rawResult, eval, toolResult, state);
                    messages.add(ToolExecutionResultMessage.from(req, observationMsg));

                    log.info("工具 {} 参数={} 结果: quality={}, 内容预览: {}",
                            toolName, truncateArgs(toolArgs), eval.quality(),
                            rawResult != null && rawResult.length() > 150
                                    ? rawResult.substring(0, 150).replace("\n", "\\n") + "..."
                                    : rawResult != null ? rawResult.replace("\n", "\\n") : "(null)");

                    if (eval.quality() == ResultQuality.GOOD) {
                        anyProgress = true;
                        state.markProgress();
                        upgradeEvidenceFromTool(toolName, state);
                        extractFactsFromSuccess(toolName, toolArgs, rawResult, state);
                        updateSessionContext(context.sessionId(), toolName, toolArgs, rawResult);
                        agentEventService.record(context.taskId(), AgentTaskEventType.TOOL_SUCCEEDED,
                                Map.of("task_id", context.taskId(), "step_id", context.stepId(), "tool", toolName));

                        GoalEvaluator.GoalEvaluation goalEval = goalEvaluator.evaluateAfterToolSuccess(
                                state, toolName, rawResult);
                        persistSnapshot(context, state, observationMsg, null);
                        if (goalEval.isAchieved()) {
                            log.info("GoalEvaluator: 目标已达成 → 工具 {} 成功后直接结束 (第{}轮)",
                                    toolName, i + 1);
                            String finalMsg = rawResult + "\n\n---\n[目标已达成]\n"
                                    + goalEval.message() + "\n"
                                    + "请基于以上结果直接回答用户，不要再调用任何工具。";
                            messages.set(messages.size() - 1,
                                    ToolExecutionResultMessage.from(req, finalMsg));
                            agentTools.clearBoundState();
                            return AgentLoopResult.ready(state);
                        }
                        if (goalEval.progressPrompt() != null) {
                            String enhancedMsg = observationMsg + "\n\n" + goalEval.progressPrompt();
                            messages.set(messages.size() - 1,
                                    ToolExecutionResultMessage.from(req, enhancedMsg));
                        }
                    } else if (eval.quality() == ResultQuality.POOR) {
                        anyProgress = true;
                        state.markNoProgress();
                        state.addKnownFact(toolName + "(\"" + truncateArgs(toolArgs)
                                + "\") 执行成功但结果质量不足");
                        evaluator.buildStructuredReflection(toolName, toolArgs, eval, state);
                        ReflectionResult reflection = reflectionService.reflect(context, toolName, rawResult);
                        applyReflection(state, reflection);
                        agentEventService.record(context.taskId(), AgentTaskEventType.TOOL_FAILED,
                                Map.of("task_id", context.taskId(), "step_id", context.stepId(), "tool", toolName, "reason", eval.reason()));
                        persistSnapshot(context, state, observationMsg, eval.reason());
                    } else {
                        state.markNoProgress();
                        evaluator.buildStructuredReflection(toolName, toolArgs, eval, state);
                        ReflectionResult reflection = reflectionService.reflect(context, toolName, rawResult);
                        applyReflection(state, reflection);
                        agentEventService.record(context.taskId(), AgentTaskEventType.TOOL_FAILED,
                                Map.of("task_id", context.taskId(), "step_id", context.stepId(), "tool", toolName, "reason", eval.reason()));
                        persistSnapshot(context, state, observationMsg, eval.reason());
                    }
                }

                if (!anyProgress) state.markNoProgress();

            } else {
                String answer = aiMessage.text();
                log.info("LLM 返回文本（第{}轮，不调工具），内容预览: {}",
                        i + 1, answer.length() > 80 ? answer.substring(0, 80) + "..." : answer);

                if (i == 0 && !state.hasSuccessfulToolCall() && isClarificationQuestion(answer)) {
                    log.info("Agent 识别为反问澄清: {}", answer.length() > 80 ? answer.substring(0, 80) + "..." : answer);
                    persistSnapshot(context, state, answer, "需要用户澄清");
                    agentTools.clearBoundState();
                    return AgentLoopResult.needClarification(state, answer);
                }

                if (!state.hasSuccessfulToolCall() && shouldForceRagBeforeDirectAnswer(context.activeTools(), state.getOriginalQuery())) {
                    log.info("Agent Loop 强制先检索知识库/笔记: query={}", state.getOriginalQuery());
                    state.markNoProgress();
                    messages.add(UserMessage.from("""
                            [检索策略]
                            当前知识库或笔记检索工具已开启。这个问题属于知识讲解/技术问答类问题，请先调用 ragSummary 获取用户资料中的相关内容。
                            不要直接用通用知识回答；如果 ragSummary 没有结果，再说明资料中未找到，并补充必要的通用解释。
                            检索 query：%s
                            """.formatted(state.getOriginalQuery())));
                    persistSnapshot(context, state, null, null);
                    continue;
                }

                GoalEvaluator.GoalEvaluation goalEval = goalEvaluator.evaluateOnTextResponse(state, answer);
                if (goalEval.isBlocked()) {
                    log.info("GoalEvaluator 拦截 LLM 回答（第{}轮）: {}",
                            i + 1, goalEval.message());
                    state.markNoProgress();
                    messages.add(UserMessage.from(goalEval.progressPrompt()));
                    persistSnapshot(context, state, answer, goalEval.message());
                    continue;
                }

                log.info("Agent Loop 完成，共 {} 轮", i + 1);
                persistSnapshot(context, state, answer, null);
                agentTools.clearBoundState();
                return AgentLoopResult.ready(state);
            }
        }

        log.info("Agent Loop 达到最大轮次 {}", maxIterations);
        persistSnapshot(context, state, null, "达到最大轮次");
        agentTools.clearBoundState();
        return AgentLoopResult.maxRounds(state);
    }

    private void applyReflection(AgentState state, ReflectionResult reflection) {
        if (reflection == null) {
            return;
        }
        if (reflection.summary() != null && !reflection.summary().isBlank()) {
            state.setLastReflectionSummary(reflection.summary());
        }
        if (reflection.failureType() != null && !reflection.failureType().isBlank()) {
            state.setLastFailureType(reflection.failureType());
        }
        if (reflection.recommendedActions() != null) {
            for (String action : reflection.recommendedActions()) {
                state.addCandidateStrategy(action);
            }
        }
        if (reflection.shouldAskUser()) {
            state.setBlockedReason(reflection.rootCause());
        }
    }

    private void persistSnapshot(WorkerLoopContext context, AgentState state, String latestObservation, String latestFailureReason) {
        if (context.taskId() == null || context.taskId().isBlank()) {
            return;
        }
        AgentExecutionSnapshot snapshot = new AgentExecutionSnapshot(
                state.getIteration(),
                state.getToolHistory().size(),
                0,
                state.buildStateSummary(),
                latestObservation,
                latestFailureReason,
                state.getLastReflectionSummary(),
                state
        );
        agentTaskService.updateExecutionSnapshot(context.taskId(), snapshot);
    }

    // ============================================================
    // Observation 构建
    // ============================================================

    private String buildObservationMessage(
            String toolName, String args, String rawResult,
            ToolResultEvaluator.Evaluation eval, ToolResult toolResult,
            AgentState state) {

        StringBuilder sb = new StringBuilder();
        sb.append(rawResult);
        sb.append("\n\n---\n[Observation]\n");

        switch (eval.quality()) {
            case GOOD -> {
                sb.append("状态：成功\n");
                if ("generateMindMap".equals(toolName) || "generateDiagram".equals(toolName)) {
                    sb.append("产物已生成，请直接基于此结果回答用户，不要再调用此工具。\n");
                } else if ("searchNotes".equals(toolName) || "listNotes".equals(toolName)) {
                    sb.append("提示：已获得数据。如需查看具体笔记完整内容，请用返回的 noteId 调用 getNote。\n");
                    sb.append("\n⚠ 重要：如果返回结果中没有用户明确要找的内容，不要直接说\"找不到\"。\n");
                    sb.append("用户表述可能与笔记实际标题不同。先尝试换关键词/换工具重新搜索后再下结论。\n");
                }
            }
            case POOR -> {
                sb.append("状态：工具执行成功，但结果质量不足\n");
                sb.append("原因：").append(eval.reason()).append("\n");
                if (eval.suggestion() != null) sb.append("建议：").append(eval.suggestion()).append("\n");
                List<String> candidates = generateCandidateStrategies(toolName, args, state);
                if (!candidates.isEmpty()) {
                    sb.append("替代策略：\n");
                    for (String c : candidates) sb.append("  - ").append(c).append("\n");
                }
            }
            case ERROR -> {
                sb.append("状态：执行失败\n");
                String errorCode = toolResult != null ? toolResult.errorCode() : "UNKNOWN";
                sb.append("错误码：").append(errorCode).append("\n");
                sb.append("原因：").append(eval.reason()).append("\n");
                if (eval.suggestion() != null) sb.append("恢复建议：").append(eval.suggestion()).append("\n");
                sb.append("下一步：").append(buildNextStepHint(toolName, eval)).append("\n");
            }
        }

        if (!state.getWorkingMemory().isEmpty()) {
            sb.append("\n已知事实：\n");
            for (String fact : state.getWorkingMemory()) {
                sb.append("  - ").append(fact).append("\n");
            }
        }

        return sb.toString();
    }

    private String buildNextStepHint(String toolName, ToolResultEvaluator.Evaluation eval) {
        if (eval.quality() == ResultQuality.GOOD) return "继续推进任务";
        return switch (toolName) {
            case "getNote" -> "需要先通过 searchNotes 或 listNotes 获取有效的 noteId";
            case "searchNotes" -> "尝试更通用/更简短的关键词，或改用 listNotes";
            case "ragSummary" -> "换更宽泛的查询词，或改用 searchNotes";
            case "listNotes" -> "可能需要调整查询数量";
            default -> "检查参数或尝试其他工具";
        };
    }

    // ============================================================
    // 状态视图（替代旧的"反思提示"）
    // ============================================================

    /**
     * 构建环境状态视图——不是"请反思"，而是"这是当前状态，请基于它决策"。
     */
    private String buildStateViewForReflection(AgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("[环境状态]\n\n");
        sb.append(state.buildStateSummary());
        sb.append("\n请基于以上环境状态选择一个与之前不同的策略继续执行。不要直接回答用户。");
        return sb.toString();
    }

    /**
     * 重复调用拦截时，附带环境状态
     */
    private String buildStateAwareBlockMessage(String toolName, String args, AgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("[调用被拦截] ").append(toolName).append(" 使用相同参数已失败过。\n\n");

        String alternative = evaluator.suggestFixByErrorCode(toolName, args,
                inferErrorCodeFromHistory(toolName, args, state));
        sb.append("建议：").append(alternative).append("\n\n");
        sb.append(state.buildStateSummary());
        return sb.toString();
    }

    // ============================================================
    // 候选策略生成
    // ============================================================

    private List<String> generateCandidateStrategies(
            String failedTool, String failedArgs, AgentState state) {
        List<String> candidates = new ArrayList<>();
        String query = extractQueryArg(failedArgs);

        switch (failedTool) {
            case "searchNotes" -> {
                if (query != null && query.length() > 3) candidates.add("searchNotes 换更通用关键词");
                if (query != null && query.length() >= 2) candidates.add("searchNotes 搜索相关概念");
                candidates.add("listNotes 浏览全部笔记");
                if (!state.hasCalledTool("getRecentNotes")) candidates.add("getRecentNotes 获取最近笔记");
            }
            case "listNotes" -> {
                candidates.add("searchNotes 用具体关键词搜索");
                candidates.add("getRecentNotes 查看最近编辑的笔记");
            }
            case "getNote" -> {
                candidates.add("searchNotes 先用关键词搜索获取正确的 noteId");
                candidates.add("listNotes 浏览全部笔记来定位正确的 noteId");
            }
            case "ragSummary" -> {
                candidates.add("ragSummary 换更宽泛的查询词");
                candidates.add("searchNotes 在笔记中搜索相关内容");
                candidates.add("listNotes 浏览笔记目录");
            }
            default -> {
                candidates.add("searchNotes 搜索相关笔记");
                candidates.add("listNotes 浏览全部笔记");
            }
        }
        return candidates;
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private String extractQueryArg(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return null;
        try {
            Map<String, String> args = AgentService.parseToolArguments(argsJson);
            return args.getOrDefault("query", args.getOrDefault("noteId", args.getOrDefault("title", null)));
        } catch (Exception e) { return null; }
    }

    private String truncateArgs(String args) {
        if (args == null) return "";
        return args.length() <= 60 ? args : args.substring(0, 57) + "...";
    }

    private void updateSessionContext(String sessionId, String toolName, String toolArgs, String rawResult) {
        if (sessionId == null) return;
        try {
            ConversationContext ctx = contextManager.getOrCreate(sessionId);
            ctx.updateFromToolCall(toolName, toolArgs, rawResult);
        } catch (Exception e) { log.debug("更新会话上下文失败: {}", e.getMessage()); }
    }

    private void upgradeEvidenceFromTool(String toolName, AgentState state) {
        switch (toolName) {
            case "generateMindMap", "generateDiagram" -> state.upgradeEvidence(AgentState.EvidenceLevel.ARTIFACT_EVIDENCE);
            case "getNote" -> state.upgradeEvidence(AgentState.EvidenceLevel.CONTENT_EVIDENCE);
            case "searchNotes" -> state.upgradeEvidence(AgentState.EvidenceLevel.SEARCH_EVIDENCE);
            case "ragSummary" -> state.upgradeEvidence(AgentState.EvidenceLevel.SEARCH_EVIDENCE);
            case "listNotes" -> state.upgradeEvidence(AgentState.EvidenceLevel.LIST_EVIDENCE);
        }
    }

    private void extractFactsFromSuccess(String toolName, String args, String result, AgentState state) {
        switch (toolName) {
            case "generateMindMap" -> state.addKnownFact("已成功生成思维导图，任务完成");
            case "generateDiagram" -> state.addKnownFact("已成功生成图表，任务完成");
            case "searchNotes" -> {
                if (result.contains("[ID:")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[ID: ([^\\]]+)\\]").matcher(result);
                    List<String> ids = new ArrayList<>();
                    while (m.find()) ids.add(m.group(1));
                    if (!ids.isEmpty()) state.addKnownFact("searchNotes 找到了 " + ids.size() + " 篇笔记，ID: " + String.join(", ", ids));
                }
            }
            case "listNotes" -> {
                if (result.contains("[ID:")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[ID: ([^\\]]+)\\]").matcher(result);
                    List<String> ids = new ArrayList<>();
                    while (m.find()) ids.add(m.group(1));
                    if (!ids.isEmpty()) state.addKnownFact("listNotes 列出了 " + ids.size() + " 篇笔记");
                }
            }
            case "getNote" -> {
                java.util.regex.Matcher titleMatch = java.util.regex.Pattern.compile("^# (.+)$", java.util.regex.Pattern.MULTILINE).matcher(result);
                if (titleMatch.find()) state.addKnownFact("已成功读取笔记《" + titleMatch.group(1) + "》的完整内容");
            }
            case "getNoteStats" -> {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("总计 (\\d+) 条笔记").matcher(result);
                if (m.find()) state.addKnownFact("用户共有 " + m.group(1) + " 条笔记");
            }
        }
    }

    private String inferErrorCodeFromHistory(String toolName, String args, AgentState state) {
        for (int i = state.getToolHistory().size() - 1; i >= 0; i--) {
            AgentState.ToolCallRecord r = state.getToolHistory().get(i);
            if (r.toolName().equals(toolName) && r.args() != null && r.args().equals(args)) {
                if (r.result().contains("NOTE_NOT_FOUND")) return "NOTE_NOT_FOUND";
                if (r.result().contains("RAG_ERROR")) return "RAG_ERROR";
                if (r.result().contains("SEARCH_ERROR")) return "SEARCH_ERROR";
                return "UNKNOWN_ERROR";
            }
        }
        return "UNKNOWN_ERROR";
    }

    // ============================================================
    // 工具执行
    // ============================================================

    private String executeTool(String toolName, String arguments, String userId,
                               List<ToolSpecification> activeTools) {
        try {
            if (!hasActiveTool(activeTools, toolName)) {
                String message = "工具已被当前开关禁用: " + toolName;
                log.warn("Blocked disabled tool execution: tool={}, activeTools={}",
                        toolName, activeTools == null ? List.of() : activeTools.stream().map(ToolSpecification::name).toList());
                agentTools.setResult(ToolResult.error(message, "TOOL_DISABLED", false));
                return message;
            }

            Map<String, String> args = AgentService.parseToolArguments(arguments);
            return switch (toolName) {
                case "listNotes" -> agentTools.listNotes(args.getOrDefault("count", "20"), args.getOrDefault("category", ""), userId);
                case "getNote" -> agentTools.getNote(args.getOrDefault("noteId", ""), userId);
                case "ragSummary" -> agentTools.ragSummary(args.getOrDefault("query", ""), userId);
                case "searchNotes" -> agentTools.searchNotes(args.getOrDefault("query", ""), userId);
                case "getNoteStats" -> agentTools.getNoteStats(userId);
                case "getRecentNotes" -> agentTools.getRecentNotes(args.getOrDefault("count", "3"), userId);
                case "getTodayReviews" -> agentTools.getTodayReviews(userId);
                case "markReviewed" -> agentTools.markReviewed(args.getOrDefault("noteId", ""), userId);
                case "createNote" -> agentTools.createNote(args.getOrDefault("title", ""), args.getOrDefault("content", ""), userId);
                case "editNote" -> agentTools.editNote(args.getOrDefault("noteId", ""), args.getOrDefault("title", ""), args.getOrDefault("content", ""), userId);
                case "appendNote" -> agentTools.appendNote(args.getOrDefault("noteId", ""), args.getOrDefault("appendContent", ""), userId);
                case "deleteNote" -> agentTools.deleteNote(args.getOrDefault("noteId", ""), userId);
                case "getRelatedNotes" -> agentTools.getRelatedNotes(args.getOrDefault("noteId", ""), userId);
                case "mergeNotes" -> agentTools.mergeNotes(args.getOrDefault("noteIds", ""), args.getOrDefault("newTitle", "合并笔记"), userId);
                case "fetchUrl" -> agentTools.fetchUrl(args.getOrDefault("url", ""));
                case "generateDiagram" -> agentTools.generateDiagram(args.getOrDefault("type", "flowchart"), args.getOrDefault("description", ""));
                case "scheduleReview" -> agentTools.scheduleReview(args.getOrDefault("noteId", ""), args.getOrDefault("days", "1"), userId);
                case "generateMindMap" -> agentTools.generateMindMap(args.getOrDefault("noteId", ""), userId);
                case "whatTimeIsNow" -> agentTools.whatTimeIsNow();
                default -> "未知工具: " + toolName;
            };
        } catch (Exception e) {
            log.error("工具 '{}' 执行失败: {}", toolName, e.getMessage());
            agentTools.setResult(ToolResult.error("工具执行失败: " + e.getMessage(), "EXCEPTION", true));
            return "工具执行失败: " + e.getMessage();
        }
    }

    private ToolResult getLastToolResult() {
        try { return agentTools.getLastResult(); } catch (Exception e) { return null; }
    }

    private void sendSseEvent(SseEmitter emitter, String type, Map<String, Object> data) throws IOException {
        if (emitter == null) return;
        Map<String, Object> event = new java.util.HashMap<>();
        event.put("type", type);
        event.putAll(data);
        try { emitter.send(SseEmitter.event().data(event)); }
        catch (IllegalStateException e) { log.debug("SSE send skipped (emitter completed): type={}", type); }
        catch (IOException e) { log.debug("SSE send skipped (client disconnected): type={}, msg={}", type, e.getMessage()); }
    }

    /**
     * 每轮开始时的思考追踪事件，包含轮次和状态信息，提升可观测性。
     */
    private void sendRoundThinking(SseEmitter emitter, int round, AgentState state) throws IOException {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("round", round);
        data.put("max_rounds", MAX_ITERATIONS);

        if (state.hasGoal()) {
            data.put("goal", state.getGoal());
        }

        int goodCalls = (int) state.getToolHistory().stream()
                .filter(r -> r.quality() == AgentState.ResultQuality.GOOD).count();
        if (goodCalls > 0) {
            data.put("successful_tools", goodCalls);
        }

        if (!state.getWorkingMemory().isEmpty()) {
            List<String> recentFacts = state.getWorkingMemory()
                    .subList(Math.max(0, state.getWorkingMemory().size() - 3), state.getWorkingMemory().size());
            data.put("recent_facts", recentFacts);
        }

        data.put("tools_called", (int) state.getToolHistory().stream()
                .map(AgentState.ToolCallRecord::toolName).distinct().count());

        sendSseEvent(emitter, "thinking", data);
    }

    /**
     * 判断 LLM 回复是否为反问澄清（替代独立的 ClarifierService）。
     *
     * 启发式规则：
     * 1. 包含问号
     * 2. 包含反问引导词（你能、你想、请说明、具体等）
     * 3. 回复较短（< 200 字，反问通常简短）
     * 4. 不包含 Markdown 标题（最终回答通常有结构）
     */
    private boolean isClarificationQuestion(String text) {
        if (text == null || text.isBlank()) return false;

        String trimmed = text.trim();

        // 规则1: 必须包含问号
        if (!trimmed.contains("？") && !trimmed.contains("?")) return false;

        // 规则2: 反问通常较短
        if (trimmed.length() > 200) return false;

        // 规则3: 最终回答通常有 ## 标题结构，反问没有
        if (trimmed.contains("##") || trimmed.contains("**")) return false;

        // 规则4: 检查反问引导词
        String[] clarificationPatterns = {
                "你能", "你想", "请说明", "具体", "哪方面", "哪个",
                "什么样", "怎么", "可以告诉", "请提供", "能否",
                "what", "which", "could you", "can you"
        };
        String lower = trimmed.toLowerCase();
        for (String pattern : clarificationPatterns) {
            if (lower.contains(pattern.toLowerCase())) return true;
        }

        return false;
    }

    private boolean shouldForceRagBeforeDirectAnswer(List<ToolSpecification> activeTools, String query) {
        return hasActiveTool(activeTools, "ragSummary") && isKnowledgeSeekingQuery(query);
    }

    private boolean hasActiveTool(List<ToolSpecification> activeTools, String toolName) {
        if (activeTools == null) return false;
        return activeTools.stream().anyMatch(tool -> toolName.equals(tool.name()));
    }

    private boolean isKnowledgeSeekingQuery(String query) {
        if (query == null || query.isBlank()) return false;
        String q = query.toLowerCase(Locale.ROOT);

        boolean operational = containsAny(q,
                "创建", "新建", "编辑", "修改", "删除", "追加", "写入", "写回", "保存", "生成导图",
                "安排", "标记", "合并", "create", "edit", "delete", "append", "save");
        if (operational) return false;

        boolean explicitKnowledge = containsAny(q,
                "讲解", "解释", "介绍", "说明", "总结", "概述", "是什么", "有哪些", "区别",
                "原理", "机制", "流程", "模型", "结构", "分区", "分类", "面试", "八股",
                "explain", "summary", "overview", "what is", "how does");
        boolean technicalTopic = containsAny(q,
                "jvm", "java", "spring", "redis", "mysql", "gc", "线程", "并发", "集合",
                "虚拟机", "内存", "堆", "栈", "方法区", "类加载", "垃圾回收", "算法",
                "rag", "bm25", "向量", "embedding", "chroma");
        return explicitKnowledge || technicalTopic;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}
