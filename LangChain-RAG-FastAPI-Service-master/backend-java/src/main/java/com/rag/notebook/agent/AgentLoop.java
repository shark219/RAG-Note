package com.rag.notebook.agent;

import com.rag.notebook.agent.AgentState.ResultQuality;
import com.rag.notebook.agent.policy.AgentPolicyService;
import com.rag.notebook.agent.policy.BudgetConfig;
import com.rag.notebook.agent.runtime.*;
import com.rag.notebook.agent.trace.AgentTraceService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
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
    private final ToolExecutionService toolExecutionService;
    private final AgentPolicyService agentPolicyService;
    private final AgentTraceService agentTraceService;

    public AgentLoop(AgentTools agentTools, ToolResultEvaluator evaluator,
                     ModelFactory modelFactory, GoalEvaluator goalEvaluator,
                     ConversationContextManager contextManager,
                     ReflectionService reflectionService,
                     ReplanningService replanningService,
                     AgentTaskService agentTaskService,
                     AgentEventService agentEventService,
                     ToolExecutionService toolExecutionService,
                     AgentPolicyService agentPolicyService,
                     AgentTraceService agentTraceService) {
        this.agentTools = agentTools;
        this.evaluator = evaluator;
        this.modelFactory = modelFactory;
        this.goalEvaluator = goalEvaluator;
        this.contextManager = contextManager;
        this.reflectionService = reflectionService;
        this.replanningService = replanningService;
        this.agentTaskService = agentTaskService;
        this.agentEventService = agentEventService;
        this.toolExecutionService = toolExecutionService;
        this.agentPolicyService = agentPolicyService;
        this.agentTraceService = agentTraceService;
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

        // 记录 trace 开始时间（用于计算总耗时）
        long traceStartTime = System.currentTimeMillis();

        // 构建预算配置
        int maxIterations = context.maxIterations() != null ? context.maxIterations() : MAX_ITERATIONS;
        int maxToolCalls = context.maxToolCalls() != null ? context.maxToolCalls() : 20;
        BudgetConfig budget = BudgetConfig.builder()
                .maxIterations(maxIterations)
                .maxToolCalls(maxToolCalls)
                .maxTokens(context.maxTokens() != null ? context.maxTokens() : 32000)
                .maxRuntimeSeconds(600)
                .maxConsecutiveFailures(5)
                .maxSameToolCalls(3)
                .build();

        // 前置条件检查：失败步骤依赖检测
        if (state.isFetchStepFailed() && requiresFetchedContent(context)) {
            log.warn("前置检查：fetchStepFailed=true 且当前步骤需要网页内容，无法继续");
            state.setBlockedReason("前置网页抓取失败，当前步骤依赖网页内容，无法继续执行");
            state.setTaskStatus(TaskStatus.GOAL_FAILED);
            saveTokenStats(state);
            saveTokenStats(state);
            agentTools.clearBoundState();
            return AgentLoopResult.blocked(state, "前置网页抓取失败，当前步骤需要网页内容作为输入");
        }

        // 前置条件检查：createNote 步骤需要网页内容
        if (isCreateNoteStep(context) && (state.getSharedFetchedContent() == null || state.getSharedFetchedContent().isBlank())) {
            log.warn("前置检查：createNote 步骤但 sharedFetchedContent 为空，可能产生幻觉内容");
            // 不阻止执行，但记录警告，让 GoalEvaluator 在 LLM 回答时拦截
            state.addKnownFact("警告：没有抓取到网页内容，创建笔记可能包含编造的信息");
        }

        if (context.goal() != null && !context.goal().isBlank()) {
            state.setGoal(context.goal());
            state.setSuccessCriteria(context.successCriteria());
            log.info("目标: goal={}, successCriteria={}", context.goal(), context.successCriteria());
        } else {
            state.setTaskStatus(TaskStatus.EXECUTING);
        }

        // 检测用户是否回复了澄清请求
        String userQuery = context.userQuery();
        if (userQuery != null && userQuery.contains("选择选项")) {
            int choice = extractUserChoice(userQuery);
            if (choice > 0) {
                log.info("检测到用户选择: choice={}, query={}", choice, userQuery);
                AgentLoopResult result = handleUserClarificationChoice(context, state, choice);
                if (result != null) {
                    // 返回 blocked 或其他终止状态
                    return result;
                }
                // result == null 表示选择了"搜索本地笔记"，继续执行当前 loop
                log.info("用户选择继续执行，将搜索本地笔记");
            }
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(context.systemPrompt()));
        messages.addAll(context.messages());
        messages.add(UserMessage.from(context.userQuery()));

        for (int i = 0; i < maxIterations; i++) {
            state.setIteration(i + 1);
            agentTraceService.incrementLoopCount();
            persistSnapshot(context, state, null, null);
            log.info("Agent Loop 第 {} 轮", i + 1);

            // 预算检查
            if (!agentPolicyService.checkBudget(budget, state, i + 1)) {
                String reason = agentPolicyService.getBudgetViolationReason(budget, state, i + 1);
                state.setBlockedReason(reason);
                state.setLastFailureType("BUDGET_EXCEEDED");
                persistSnapshot(context, state, null, reason);
                saveTokenStats(state);
            agentTools.clearBoundState();
                return AgentLoopResult.maxRounds(state);
            }

            if (state.needsReflection()) {
                emitThinking(context.emitter(), "reflecting", "检测到连续失败，正在分析原因");
                ReflectionResult reflection = reflectionService.reflect(context, null, null);
                if (reflection.summary() != null && !reflection.summary().isBlank()) {
                    state.setLastReflectionSummary(reflection.summary());
                    state.setLastFailureType(reflection.failureType());
                    agentEventService.recordAndEmit(context.taskId(), AgentTaskEventType.REFLECTION_CREATED,
                            Map.of("task_id", context.taskId(),
                                   "step_id", context.stepId() != null ? context.stepId() : "",
                                   "summary", reflection.summary() != null ? reflection.summary() : ""),
                            context.emitter());
                    emitThinking(context.emitter(), "reflected", "已完成反思分析: " + reflection.summary());
                }
                emitThinking(context.emitter(), "replanning", "正在调整执行策略");
                ReplanResult replanResult = replanningService.replan(context, reflection);
                if (replanResult.summary() != null && !replanResult.summary().isBlank()) {
                    agentEventService.recordAndEmit(context.taskId(), AgentTaskEventType.REPLAN_CREATED,
                            Map.of("task_id", context.taskId(),
                                   "step_id", context.stepId() != null ? context.stepId() : "",
                                   "summary", replanResult.summary() != null ? replanResult.summary() : ""),
                            context.emitter());
                    emitThinking(context.emitter(), "replanned", "已调整策略: " + replanResult.summary());

                    // 记录重规划
                    agentTraceService.recordReplanning(
                            reflection.summary() != null ? reflection.summary() : "连续失败触发重规划",
                            replanResult.summary() != null ? replanResult.summary() : ""
                    );
                }
                String stateView = buildStateViewForReflection(state);
                messages.add(UserMessage.from(stateView));
                state.markProgress();
                log.info("注入环境状态视图（连续{}轮无进展）:\n{}",
                        state.getConsecutiveNoProgress() + 1, stateView);
            }

            if (state.getCurrentStepInstructions() != null && !state.getCurrentStepInstructions().isBlank() && i == 0) {
                messages.add(UserMessage.from("[步骤执行约束]\n" + state.getCurrentStepInstructions()));
            }

            sendRoundThinking(context.emitter(), i + 1, state);

            ChatLanguageModel llm = modelFactory.createPreciseModel();
            long llmStart = System.currentTimeMillis();
            Response<AiMessage> response = llm.generate(messages, context.activeTools());
            long llmLatency = System.currentTimeMillis() - llmStart;
            AiMessage aiMessage = response.content();

            // 统计 Token 消耗
            if (response.tokenUsage() != null) {
                int tokensUsed = response.tokenUsage().totalTokenCount();
                state.addTokensConsumed(tokensUsed);
                log.debug("本轮 Token 消耗: {}, 累计: {}", tokensUsed, state.getTotalTokensConsumed());
            }

            // 记录 LLM 交互
            agentTraceService.recordLLMInteraction(
                    "assistant",
                    aiMessage.text() != null ? aiMessage.text() : "[Tool calls]",
                    response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0,
                    response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0,
                    System.currentTimeMillis() - llmStart
            );

            if (aiMessage.hasToolExecutionRequests()) {
                messages.add(aiMessage);
                boolean anyProgress = false;

                List<String> toolNames = aiMessage.toolExecutionRequests().stream()
                        .map(ToolExecutionRequest::name).toList();
                log.info("LLM 决定调用工具: {} (第{}轮)", toolNames, i + 1);

                int totalTools = aiMessage.toolExecutionRequests().size();
                int toolIndex = 0;
                for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                    // 预算检查（工具调用次数）
                    if (!agentPolicyService.checkBudget(budget, state, i + 1)) {
                        String reason = agentPolicyService.getBudgetViolationReason(budget, state, i + 1);
                        state.setBlockedReason(reason);
                        state.setLastFailureType("BUDGET_EXCEEDED");
                        persistSnapshot(context, state, null, reason);
                        saveTokenStats(state);
            agentTools.clearBoundState();
                        return AgentLoopResult.maxRounds(state);
                    }

                    toolIndex++;
                    String toolName = req.name();
                    String toolArgs = req.arguments();

                    long toolStart = System.currentTimeMillis();

                    if (!state.isToolAllowed(toolName)) {
                        String blocked = "当前步骤禁止调用工具: " + toolName;
                        messages.add(ToolExecutionResultMessage.from(req, blocked));
                        state.addFailedAction(toolName + "(" + toolArgs + ")");
                        state.addKnownFact("禁止调用非白名单工具：" + toolName);

                        // 记录工具调用失败
                        long toolLatency = System.currentTimeMillis() - toolStart;
                        agentTraceService.recordToolCall(toolName, toolArgs,
                                blocked, toolLatency, false, "工具不在白名单中");
                        continue;
                    }

                    // 审批检查
                    if (agentPolicyService.requiresApproval(toolName, toolArgs, state)) {
                        log.warn("工具 {} 需要用户审批", toolName);
                        String approvalMsg = String.format("工具 %s 需要用户审批。参数: %s", toolName, toolArgs);
                        state.setBlockedReason(approvalMsg);
                        persistSnapshot(context, state, null, state.getBlockedReason());
                        saveTokenStats(state);
            agentTools.clearBoundState();
                        return AgentLoopResult.needClarification(state, approvalMsg);
                    }

                    // 频率限制检查
                    if (!agentPolicyService.checkRateLimit(toolName, context.userId(), state)) {
                        log.warn("工具 {} 超过频率限制", toolName);
                        messages.add(ToolExecutionResultMessage.from(req,
                                "工具调用超过频率限制: " + toolName));
                        state.addFailedAction(toolName + "(" + toolArgs + ")");
                        continue;
                    }

                    // fetchUrl 白名单检查
                    if ("fetchUrl".equals(toolName)) {
                        Map<String, String> args = AgentService.parseToolArguments(toolArgs);
                        String url = args.getOrDefault("url", "");
                        if (!agentPolicyService.isUrlAllowed(url)) {
                            log.warn("URL 不在白名单中: {}", url);
                            messages.add(ToolExecutionResultMessage.from(req,
                                    "URL 不在允许的白名单中，无法访问: " + url));
                            // 记录失败动作时包含"白名单"关键词，便于后续检测
                            state.addFailedAction(toolName + "(" + toolArgs + ") [白名单拦截]");
                            state.addKnownFact("URL 访问受限：" + url);

                            // 检查是否应该询问用户
                            if (shouldAskUserAboutUrlWhitelist(state)) {
                                String blockedUrl = extractBlockedUrl(state);
                                String clarificationMsg = String.format(
                                    "无法访问该网页：%s\n\n" +
                                    "原因：该 URL 不在允许的白名单中。\n\n" +
                                    "您希望如何继续？\n" +
                                    "1. 添加该域名到白名单（需要管理员权限）\n" +
                                    "2. 改为搜索本地笔记库中的相关内容\n" +
                                    "3. 取消此次操作",
                                    blockedUrl
                                );
                                log.info("URL 白名单限制，询问用户: {}", blockedUrl);
                                persistSnapshot(context, state, clarificationMsg, "URL 白名单限制");
                                saveTokenStats(state);
            agentTools.clearBoundState();
                                return AgentLoopResult.needClarification(state, clarificationMsg);
                            }

                            continue;
                        }
                    }

                    agentEventService.recordAndEmit(context.taskId(), AgentTaskEventType.TOOL_CALLED,
                            Map.of("task_id", context.taskId(),
                                   "step_id", context.stepId() != null ? context.stepId() : "",
                                   "tool", toolName != null ? toolName : "",
                                   "args", toolArgs != null ? toolArgs : ""),
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

                    // 使用 ToolExecutionService 执行工具
                    emitThinking(context.emitter(), "tool_executing", "正在执行工具: " + toolName);
                    ToolExecutionResult execResult = toolExecutionService.execute(
                            toolName, toolArgs, context.userId(), context.taskId(), context.sessionId(), state);

                    String rawResult;
                    ToolResult toolResult;
                    long toolLatency = System.currentTimeMillis() - toolStart;

                    if (execResult.isSuccess()) {
                        rawResult = execResult.getResult();
                        toolResult = execResult.getToolResult();
                        emitThinking(context.emitter(), "tool_executed", "工具执行成功: " + toolName);

                        // 记录成功的工具调用到 trace
                        agentTraceService.recordToolCall(toolName, toolArgs, rawResult, toolLatency, true, null);

                        // 如果是 ragSummary，关联 RAG trace ID
                        if ("ragSummary".equals(toolName)) {
                            String ragTraceId = agentTools.getLatestTraceId();
                            if (ragTraceId != null) {
                                agentTraceService.recordRagTraceId(ragTraceId);
                            }
                        }
                    } else {
                        rawResult = "工具执行失败: " + execResult.getErrorMessage();
                        toolResult = ToolResult.error(execResult.getErrorMessage(),
                                execResult.getErrorCode(), false);
                        agentTools.setResult(toolResult);
                        emitThinking(context.emitter(), "tool_failed", "工具执行失败: " + toolName);

                        // 记录失败的工具调用到 trace
                        agentTraceService.recordToolCall(toolName, toolArgs, rawResult, toolLatency, false, execResult.getErrorMessage());
                        agentTraceService.recordError("TOOL_EXECUTION_ERROR", execResult.getErrorMessage(), toolName, null);
                    }

                    emitThinking(context.emitter(), "evaluating", "正在评估工具执行结果");
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
                        state.resetCurrentStepFailureCount();
                        upgradeEvidenceFromTool(toolName, state);
                        extractFactsFromSuccess(toolName, toolArgs, rawResult, state);
                        updateSessionContext(context.sessionId(), toolName, toolArgs, rawResult);
                        agentEventService.record(context.taskId(), AgentTaskEventType.TOOL_SUCCEEDED,
                                Map.of("task_id", context.taskId(),
                                       "step_id", context.stepId() != null ? context.stepId() : "",
                                       "tool", toolName != null ? toolName : ""));

                        emitThinking(context.emitter(), "goal_evaluating", "正在评估目标达成情况");
                        GoalEvaluator.GoalEvaluation goalEval = goalEvaluator.evaluateAfterToolSuccess(
                                state, toolName, rawResult);
                        persistSnapshot(context, state, observationMsg, null);
                        if (goalEval.isAchieved()) {
                            log.info("GoalEvaluator: 目标已达成 → 工具 {} 成功后直接结束 (第{}轮)",
                                    toolName, i + 1);
                            emitThinking(context.emitter(), "goal_achieved", "目标已达成: " + goalEval.message());
                            emitThinking(context.emitter(), "goal_achieved", "目标已达成，准备生成最终回答");
                            String finalMsg = rawResult + "\n\n---\n[目标已达成]\n"
                                    + goalEval.message() + "\n"
                                    + "请基于以上结果直接回答用户，不要再调用任何工具。";
                            messages.set(messages.size() - 1,
                                    ToolExecutionResultMessage.from(req, finalMsg));
                            saveTokenStats(state);
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
                        state.incrementCurrentStepFailureCount();
                        state.addKnownFact(toolName + "(\"" + truncateArgs(toolArgs)
                                + "\") 执行成功但结果质量不足");
                        evaluator.buildStructuredReflection(toolName, toolArgs, eval, state);
                        ReflectionResult reflection = reflectionService.reflect(context, toolName, rawResult);
                        applyReflection(state, reflection);
                        agentEventService.record(context.taskId(), AgentTaskEventType.TOOL_FAILED,
                                Map.of("task_id", context.taskId(),
                                       "step_id", context.stepId() != null ? context.stepId() : "",
                                       "tool", toolName != null ? toolName : "",
                                       "reason", eval.reason() != null ? eval.reason() : ""));
                        persistSnapshot(context, state, observationMsg, eval.reason());
                        if (shouldStopCurrentStep(state, toolName, eval, rawResult)) {
                            saveTokenStats(state);
            agentTools.clearBoundState();
                            return AgentLoopResult.maxRounds(state);
                        }
                    } else {
                        state.markNoProgress();
                        state.incrementCurrentStepFailureCount();
                        evaluator.buildStructuredReflection(toolName, toolArgs, eval, state);
                        ReflectionResult reflection = reflectionService.reflect(context, toolName, rawResult);
                        applyReflection(state, reflection);
                        agentEventService.record(context.taskId(), AgentTaskEventType.TOOL_FAILED,
                                Map.of("task_id", context.taskId(),
                                       "step_id", context.stepId() != null ? context.stepId() : "",
                                       "tool", toolName != null ? toolName : "",
                                       "reason", eval.reason() != null ? eval.reason() : ""));
                        persistSnapshot(context, state, observationMsg, eval.reason());
                        if (shouldStopCurrentStep(state, toolName, eval, rawResult)) {
                            saveTokenStats(state);
            agentTools.clearBoundState();
                            return AgentLoopResult.maxRounds(state);
                        }
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
                    saveTokenStats(state);
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
                saveTokenStats(state);
            agentTools.clearBoundState();
                return AgentLoopResult.ready(state);
            }
        }

        log.info("Agent Loop 达到最大轮次 {}", maxIterations);

        // 循环退出后检查是否是关键步骤失败
        if ("FETCH".equals(state.getCurrentTaskType()) && state.getCurrentStepFailureCount() >= 3) {
            state.setFetchStepFailed(true);
            state.setBlockedReason("FETCH_MAX_RETRIES_EXCEEDED");
            state.addKnownFact("抓取步骤达到最大重试次数，所有尝试均失败。");
            log.info("抓取步骤失败，设置 fetchStepFailed = true");
        }

        persistSnapshot(context, state, null, "达到最大轮次");
        saveTokenStats(state);
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

    /**
     * 保存 Token 统计到 trace（退出前调用）
     */
    private void saveTokenStats(AgentState state) {
        agentTraceService.recordTokenStats(
                state.getTotalTokensConsumed(),
                0, // systemPromptTokens - TODO: 单独统计
                0, // historyTokens - TODO: 单独统计
                0  // toolResultTokens - TODO: 单独统计
        );
    }

    private boolean shouldStopCurrentStep(AgentState state,
                                          String toolName,
                                          ToolResultEvaluator.Evaluation eval,
                                          String rawResult) {
        if (!"FETCH".equals(state.getCurrentTaskType())) {
            return false;
        }
        if (!"fetchUrl".equals(toolName)) {
            return false;
        }
        if (state.getCurrentStepFailureCount() < 3) {
            return false;
        }

        // 搜索结果页检测
        String lowered = rawResult == null ? "" : rawResult.toLowerCase(Locale.ROOT);
        if (lowered.contains("bing.com/search") || lowered.contains("baidu.com/s")
                || lowered.contains("sogou.com") || lowered.contains("so.com/s") || lowered.contains("search?q=")) {
            state.setBlockedReason("FETCH_SEARCH_RESULT_PAGE");
            state.addKnownFact("抓取结果已偏离原网页，命中了搜索结果页，终止当前抓取步骤。");
            return true;
        }

        // fetchUrl 连续失败 3 次，无论原因，都应该终止
        // 不再依赖 eval.reason() 的具体措辞，避免漏判
        if (eval.quality() == ResultQuality.ERROR || eval.quality() == ResultQuality.POOR) {
            state.setBlockedReason("FETCH_SOURCE_UNAVAILABLE");
            state.setFetchStepFailed(true);
            state.addKnownFact("源网页连续抓取失败（" + eval.reason() + "），已达到失败预算，终止当前抓取步骤。");
            log.info("抓取步骤失败，设置 fetchStepFailed = true，原因: {}", eval.reason());
            return true;
        }

        return false;
    }

    private void persistSnapshot(WorkerLoopContext context, AgentState state, String latestObservation, String latestFailureReason) {
        if (context.taskId() == null || context.taskId().isBlank()) {
            return;
        }
        AgentExecutionSnapshot snapshot = new AgentExecutionSnapshot(
                state.getIteration(),
                state.getToolHistory().size(),
                state.getTotalTokensConsumed(),
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

    /**
     * 判断当前步骤是否需要网页内容
     */
    private boolean requiresFetchedContent(WorkerLoopContext context) {
        if (context.goal() == null) return false;
        String goal = context.goal().toLowerCase(Locale.ROOT);
        // createNote 步骤通常需要网页内容
        return goal.contains("创建") && goal.contains("笔记") && goal.contains("总结");
    }

    /**
     * 判断当前步骤是否为 createNote 步骤
     */
    private boolean isCreateNoteStep(WorkerLoopContext context) {
        if (context.goal() == null) return false;
        String goal = context.goal().toLowerCase(Locale.ROOT);
        return goal.contains("创建") && goal.contains("笔记");
    }

    /**
     * 判断是否应该询问用户关于 URL 白名单
     */
    private boolean shouldAskUserAboutUrlWhitelist(AgentState state) {
        if (state.getFailedActions().size() < 3) return false;

        // 检查最近 3 次失败是否都是 fetchUrl + 白名单拦截
        long urlBlockedCount = state.getFailedActions().stream()
                .filter(a -> a.contains("fetchUrl") &&
                           (a.contains("白名单") || a.contains("不在允许") ||
                            a.contains("访问受限") || a.contains("被拦截")))
                .count();

        return urlBlockedCount >= 3;
    }

    /**
     * 从失败操作中提取被拦截的 URL
     */
    private String extractBlockedUrl(AgentState state) {
        for (String action : state.getFailedActions()) {
            if (action.contains("fetchUrl") && action.contains("url")) {
                // 尝试从 JSON 参数中提取 URL
                int urlStart = action.indexOf("\"url\"");
                if (urlStart != -1) {
                    int colonPos = action.indexOf(":", urlStart);
                    int quoteStart = action.indexOf("\"", colonPos + 1);
                    int quoteEnd = action.indexOf("\"", quoteStart + 1);
                    if (quoteStart != -1 && quoteEnd != -1) {
                        return action.substring(quoteStart + 1, quoteEnd);
                    }
                }
            }
        }
        return "未知 URL";
    }

    /**
     * 从用户消息中提取选择的选项编号
     */
    private int extractUserChoice(String userQuery) {
        if (userQuery == null) return -1;

        // 匹配 "选择选项 1" "选择选项 2" 等格式
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("选择选项\\s*(\\d+)");
        java.util.regex.Matcher matcher = pattern.matcher(userQuery);

        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return -1;
    }

    /**
     * 处理用户的澄清选择
     */
    private AgentLoopResult handleUserClarificationChoice(WorkerLoopContext context, AgentState state, int choice) {
        String blockedUrl = extractBlockedUrl(state);

        switch (choice) {
            case 1: // 添加到白名单
                log.info("用户选择添加域名到白名单: {}", blockedUrl);
                state.setTaskStatus(TaskStatus.GOAL_FAILED);
                state.setBlockedReason("需要管理员权限添加域名到白名单");
                saveTokenStats(state);
            agentTools.clearBoundState();
                return AgentLoopResult.blocked(state,
                    String.format("已收到您的选择。\n\n" +
                        "您选择了「添加该域名到白名单」。\n\n" +
                        "这需要管理员权限进行系统配置。请联系系统管理员将域名 `%s` 添加到 URL 白名单中，" +
                        "然后重新尝试该任务。", extractDomain(blockedUrl)));

            case 2: // 搜索本地笔记 - 返回 null 表示继续执行当前 loop
                log.info("用户选择搜索本地笔记替代网页抓取");
                // 修改状态：标记 fetchStep 失败，但允许继续
                state.setFetchStepFailed(true);
                state.addKnownFact("用户选择放弃网页抓取，改为搜索本地笔记");

                // 清空失败记录，开始新的执行
                state.getFailedActions().clear();
                state.setTaskStatus(TaskStatus.EXECUTING);

                // 返回 null 表示不立即结束，让调用方继续执行 AgentLoop
                return null;

            case 3: // 取消操作
                log.info("用户选择取消操作");
                state.setTaskStatus(TaskStatus.GOAL_FAILED);
                state.setBlockedReason("用户主动取消操作");
                saveTokenStats(state);
            agentTools.clearBoundState();
                return AgentLoopResult.blocked(state,
                    "好的，已取消此次操作。\n\n" +
                    "由于目标网页不在允许访问的白名单中，且您选择了取消操作，任务已终止。\n\n" +
                    "如果您想：\n" +
                    "- 添加该域名到白名单，请联系系统管理员\n" +
                    "- 搜索本地笔记中的相关内容，可以重新发起一个新的查询");

            default:
                log.warn("未知的用户选择: {}", choice);
                return AgentLoopResult.blocked(state, "抱歉，无法识别您的选择。请重新选择 1、2 或 3。");
        }
    }

    /**
     * 从 URL 中提取域名
     */
    private String extractDomain(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            return uri.getHost();
        } catch (Exception e) {
            return url;
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

    /**
     * 发送思考过程 SSE 事件到前端
     * @param emitter SSE 发射器
     * @param stage 阶段标识：reflecting, reflected, replanning, replanned, evaluating, tool_executing 等
     * @param detail 详细描述
     */
    public static void emitThinking(SseEmitter emitter, String stage, String detail) {
        if (emitter == null) return;
        try {
            Map<String, Object> data = Map.of(
                    "stage", stage,
                    "detail", detail,
                    "timestamp", System.currentTimeMillis()
            );
            emitter.send(SseEmitter.event()
                    .name("thinking")
                    .data(data));
        } catch (Exception e) {
            log.warn("发送思考过程事件失败: {}", e.getMessage());
        }
    }

    /**
     * 构建 Prompt 摘要（用于 trace 记录）
     */
    private String buildPromptSummary(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (ChatMessage msg : messages) {
            if (count >= 3) { // 只记录前3条
                sb.append("... (").append(messages.size() - count).append(" more messages)");
                break;
            }
            String type = msg.type().toString();
            String content = msg.text();
            if (content != null && content.length() > 100) {
                content = content.substring(0, 100) + "...";
            }
            sb.append("[").append(type).append("] ").append(content).append("\n");
            count++;
        }
        return sb.toString();
    }
}
