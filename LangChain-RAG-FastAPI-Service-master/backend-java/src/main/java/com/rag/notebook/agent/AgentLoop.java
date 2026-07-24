package com.rag.notebook.agent;

import com.rag.notebook.agent.AgentState.ResultQuality;
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
import java.util.Map;

/**
 * Agent 主循环：LLM 自主决策每一步，失败后基于 Observation 重新决策而非机械重试。
 *
 * 核心流程：
 *   Action → Observation → Reflect → Replan → Action
 *
 * 关键原则：
 *   工具失败 ≠ 自动重试
 *   工具失败 = 新的信息（用于重新决策）
 *
 * 不同错误类型 → 不同恢复策略：
 *   NOTE_NOT_FOUND → 换获取路径（先搜索定位）
 *   EMPTY          → 换查询策略（更宽泛的关键词）
 *   TIMEOUT        → 可以原样重试
 *   SUCCESS        → 利用结果继续
 */
@Slf4j
@Component
public class AgentLoop {

    private static final int MAX_ITERATIONS = 10;

    private final AgentTools agentTools;
    private final ToolResultEvaluator evaluator;
    private final ModelFactory modelFactory;
    private final CompletionGate completionGate;
    private final GoalEvaluator goalEvaluator;
    private final ConversationContextManager contextManager;

    public AgentLoop(AgentTools agentTools, ToolResultEvaluator evaluator,
                     ModelFactory modelFactory, CompletionGate completionGate,
                     GoalEvaluator goalEvaluator,
                     ConversationContextManager contextManager) {
        this.agentTools = agentTools;
        this.evaluator = evaluator;
        this.modelFactory = modelFactory;
        this.completionGate = completionGate;
        this.goalEvaluator = goalEvaluator;
        this.contextManager = contextManager;
    }

    /**
     * Agent 执行入口。
     *
     * @return AgentLoopResult（READY 或 MAX_ROUNDS），包含完整 AgentState。
     *         最终回答由 ResponseComposer 根据证据独立生成。
     */
    public AgentLoopResult run(String systemPrompt, String userQuery,
                      List<ChatMessage> historyMessages,
                      String userId, String sessionId,
                      List<ToolSpecification> activeTools,
                      SseEmitter emitter,
                      String forceToolHint, String forceToolDesc,
                      String goalDescription, List<String> requiredArtifacts, String stopCondition) throws IOException {

        AgentState state = new AgentState(userQuery);
        java.util.Set<String> forcedTools = new java.util.HashSet<>();

        // 从 Supervisor 建议中提取任务约束
        if (forceToolHint != null && !forceToolHint.isBlank()) {
            state.setRequiredTool(forceToolHint);
            state.setToolRequired(true);
            state.setTaskGoal(forceToolDesc);
            log.info("任务约束: requiredTool={}, goal={}", forceToolHint, forceToolDesc);
        }

        // 设置目标追踪（核心新增：让 Agent 知道"要产出什么、何时停止"）
        if (goalDescription != null && !goalDescription.isBlank()) {
            state.setGoalDescription(goalDescription);
            state.setRequiredArtifacts(requiredArtifacts);
            state.setStopCondition(stopCondition);
            state.setTaskStatus(TaskStatus.EXECUTING);
            log.info("目标追踪: goal={}, requiredArtifacts={}, stopCondition={}",
                    goalDescription, requiredArtifacts, stopCondition);
        } else if (forceToolHint != null && !forceToolHint.isBlank()) {
            // 无明确目标但有工具约束时，自动推断
            state.setGoalDescription(forceToolDesc != null ? forceToolDesc : "使用 " + forceToolHint + " 完成用户请求");
            state.setRequiredArtifacts(inferArtifactsForTool(forceToolHint));
            state.setTaskStatus(TaskStatus.EXECUTING);
        } else {
            state.setTaskStatus(TaskStatus.EXECUTING);
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.addAll(historyMessages);
        messages.add(UserMessage.from(userQuery));

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.info("Agent Loop 第 {} 轮", i + 1);

            // 如果连续无进展，在调用 LLM 前注入反思提示
            if (state.needsReflection()) {
                String reflectionPrompt = buildReflectionPrompt(state);
                messages.add(UserMessage.from(reflectionPrompt));
                state.markProgress(); // 重置计数器，避免重复注入
                log.info("注入反思提示（连续{}轮无进展）", state.getConsecutiveNoProgress() + 1);
            }

            ChatLanguageModel llm = modelFactory.createPreciseModel();
            Response<AiMessage> response = llm.generate(messages, activeTools);
            AiMessage aiMessage = response.content();

            if (aiMessage.hasToolExecutionRequests()) {
                messages.add(aiMessage);

                boolean anyProgress = false;

                for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                    String toolName = req.name();
                    String toolArgs = req.arguments();

                    sendSseEvent(emitter, "thinking", Map.of(
                            "stage", "tool_call",
                            "content", "正在调用工具: " + toolName
                    ));

                    // 防重复规则 1：同工具同参数已失败过 → 跳过
                    if (state.hasCalledWithArgsAndFailed(toolName, toolArgs)) {
                        log.info("拦截重复调用: {}({})，之前已失败", toolName, toolArgs);
                        String blockMsg = buildDuplicateBlockMessage(toolName, toolArgs, state);
                        messages.add(ToolExecutionResultMessage.from(req, blockMsg));
                        continue;
                    }

                    // 防重复规则 2：刚执行过完全相同的调用 → 跳过
                    if (state.hasSameAction(toolName, toolArgs)) {
                        log.info("拦截机械重复: {}({})，与上一轮完全相同", toolName, toolArgs);
                        messages.add(ToolExecutionResultMessage.from(req,
                                "该工具和参数刚刚已经尝试过，没有推进任务。"
                                + "请分析失败原因并选择不同参数或不同工具。"));
                        continue;
                    }

                    // 执行工具
                    String rawResult = executeTool(toolName, toolArgs, userId);
                    ToolResult toolResult = getLastToolResult();

                    // 评估结果质量
                    ToolResultEvaluator.Evaluation eval = evaluator.evaluate(
                            toolName, toolArgs, rawResult, state.getOriginalQuery(), toolResult);
                    state.recordToolCall(toolName, toolArgs, rawResult, eval.quality());

                    // 构建 Observation 消息（结构化注入）
                    String observationMsg = buildObservationMessage(
                            toolName, toolArgs, rawResult, eval, toolResult, state);
                    messages.add(ToolExecutionResultMessage.from(req, observationMsg));

                    log.info("工具 {} 结果: quality={}, errorCode={}",
                            toolName, eval.quality(),
                            toolResult != null ? toolResult.errorCode() : "N/A");

                    // 根据结果质量更新状态
                    if (eval.quality() == ResultQuality.GOOD) {
                        state.markProgress();
                        anyProgress = true;
                        upgradeEvidenceFromTool(toolName, state);
                        extractFactsFromSuccess(toolName, toolArgs, rawResult, state);
                        // 写操作成功：标记确认
                        if (isWriteTool(toolName)) {
                            state.markWriteConfirmation(toolName + " 执行成功");
                            log.info("写操作确认: {} 已成功执行", toolName);
                        }
                        // 更新会话上下文（当前活跃笔记追踪）
                        updateSessionContext(sessionId, toolName, toolArgs, rawResult);

                        // ========== 核心新增：GoalEvaluator 主动判断目标是否达成 ==========
                        GoalEvaluator.GoalEvaluation goalEval = goalEvaluator.evaluateAfterToolSuccess(state, toolName);
                        if (goalEval.isAchieved()) {
                            log.info("GoalEvaluator: 目标已达成 → 工具 {} 成功后直接结束循环", toolName);
                            // 将目标达成信息作为最终工具结果注入
                            String finalObservation = buildGoalAchievedObservation(toolName, rawResult, goalEval, state);
                            // 替换最后一条消息为目标达成版本
                            messages.set(messages.size() - 1,
                                    ToolExecutionResultMessage.from(req, finalObservation));
                            return AgentLoopResult.ready(state);
                        }
                        // 目标未达成但工具有进展：在 Observation 中追加进度信息
                        if (goalEval.progressMessage() != null) {
                            // 更新最后一条消息，附加目标进度
                            String enhancedMsg = observationMsg + "\n\n" + goalEval.progressMessage();
                            messages.set(messages.size() - 1,
                                    ToolExecutionResultMessage.from(req, enhancedMsg));
                        }
                        // ========== GoalEvaluator 检查结束 ==========
                    } else if (eval.quality() == ResultQuality.POOR) {
                        // POOR：工具执行成功但证据不足 → 算"已执行"但不升级证据
                        anyProgress = true;  // 对 Rule 1 来说，工具已成功调用
                        state.markNoProgress();  // 但对质量来说，没有获得可用数据
                        // 记录到工作记忆，但不标记为"失败"
                        state.addKnownFact(toolName + "(\"" + truncateArgs(toolArgs) + "\") 执行成功但结果质量不足");
                        // 生成反思写入工作记忆
                        evaluator.buildStructuredReflection(toolName, toolArgs, eval, state);
                    } else {
                        // ERROR：工具崩溃 → 不算已执行
                        state.markNoProgress();
                        evaluator.buildStructuredReflection(toolName, toolArgs, eval, state);
                    }
                }

                if (!anyProgress) {
                    state.markNoProgress();
                }
            } else {
                // LLM 返回文本，未调用工具
                String answer = aiMessage.text();

                // 先走 GoalEvaluator（目标驱动判断）
                GoalEvaluator.GoalEvaluation goalEval = goalEvaluator.evaluateOnTextResponse(state, answer);
                if (goalEval.isBlocked()) {
                    log.info("GoalEvaluator 拦截，原因: {}", goalEval.message());
                    state.markNoProgress();
                    String retryPrompt = goalEval.progressMessage() != null
                            ? goalEval.progressMessage()
                            : "[系统：任务尚未完成]\n\n" + goalEval.message() + "\n\n请继续调用工具完成任务。";
                    messages.add(UserMessage.from(retryPrompt));
                    continue;
                }

                if (goalEval.isAchieved()) {
                    log.info("GoalEvaluator 放行，Agent Loop 完成，共 {} 轮", i + 1);
                    return AgentLoopResult.ready(state);
                }

                // GoalEvaluator 无法判断，回退到 CompletionGate
                CompletionGate.GateResult gate = completionGate.check(state, answer);
                if (!gate.allowed()) {
                    log.info("CompletionGate 拦截，原因: {}", gate.reason());
                    state.markNoProgress();
                    String richRetryPrompt = buildRichRetryPrompt(gate, state);
                    messages.add(UserMessage.from(richRetryPrompt));
                    continue;
                }

                log.info("Agent Loop 完成，共 {} 轮，工作记忆 {} 条，证据级别 {}",
                        i + 1, state.getWorkingMemory().size(), state.getHighestEvidence());
                return AgentLoopResult.ready(state);
            }
        }

        // 达到最大轮次，返回 MAX_ROUNDS，由外层 Composer 处理
        log.info("Agent Loop 达到最大轮次 {}", MAX_ITERATIONS);
        return AgentLoopResult.maxRounds(state);
    }

    // ============================================================
    // Observation 构建
    // ============================================================

    /**
     * 构建结构化的 Observation 消息，注入到工具结果中。
     *
     * 这是整个反思循环的关键：不是把工具原始输出丢给 LLM，
     * 而是包装成"结果 + 分析 + 建议"的结构化 Observation。
     */
    private String buildObservationMessage(
            String toolName, String args, String rawResult,
            ToolResultEvaluator.Evaluation eval, ToolResult toolResult,
            AgentState state) {

        StringBuilder sb = new StringBuilder();

        // 第一部分：工具原始结果
        sb.append(rawResult);

        // 第二部分：结构化评估
        sb.append("\n\n---\n");
        sb.append("[Observation]\n");

        switch (eval.quality()) {
            case GOOD -> {
                sb.append("状态：成功\n");
                if ("generateMindMap".equals(toolName) || "generateDiagram".equals(toolName)) {
                    sb.append("任务完成：产物已生成。请直接基于工具返回的内容回答用户，不要再调用此工具。\n");
                } else if ("searchNotes".equals(toolName) || "listNotes".equals(toolName)) {
                    sb.append("提示：已获得笔记列表/搜索结果。")
                            .append("如果用户想看具体某篇笔记的内容，请用返回的 noteId 调用 getNote。\n");
                }
            }
            case POOR -> {
                sb.append("执行状态：工具执行成功，但结果质量不足\n");
                sb.append("质量评估：").append(eval.reason()).append("\n");
                sb.append("已尝试：").append(toolName).append("(").append(truncateArgs(args)).append(")\n");
                sb.append("不要重复：相同的工具和参数不会改善结果\n");
                if (eval.suggestion() != null) {
                    sb.append("恢复方向：").append(eval.suggestion()).append("\n");
                }
                // 生成候选替代策略
                List<String> candidates = generateCandidateStrategies(toolName, args, state);
                if (!candidates.isEmpty()) {
                    sb.append("候选替代策略：\n");
                    for (String c : candidates) {
                        sb.append("  - ").append(c).append("\n");
                    }
                }
            }
            case ERROR -> {
                sb.append("状态：执行失败\n");
                String errorCode = toolResult != null ? toolResult.errorCode() : "UNKNOWN";
                sb.append("错误码：").append(errorCode).append("\n");
                sb.append("原因：").append(eval.reason()).append("\n");
                if (eval.suggestion() != null) {
                    sb.append("恢复建议：").append(eval.suggestion()).append("\n");
                }
                sb.append("下一步：").append(buildNextStepHint(toolName, eval)).append("\n");
            }
        }

        // 第三部分：当前已知事实（从工作记忆中提取）
        if (!state.getWorkingMemory().isEmpty()) {
            sb.append("\n当前已知事实：\n");
            for (String fact : state.getWorkingMemory()) {
                sb.append("  - ").append(fact).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 根据工具类型和评估结果，给出具体的下一步方向
     */
    private String buildNextStepHint(String toolName, ToolResultEvaluator.Evaluation eval) {
        if (eval.quality() == ResultQuality.GOOD) return "继续推进任务";

        return switch (toolName) {
            case "getNote" -> "需要先通过 searchNotes 或 listNotes 获取有效的 noteId";
            case "searchNotes" -> "尝试用更通用/更简短的关键词，或改用 listNotes 浏览全部笔记";
            case "ragSummary" -> "尝试换个更宽泛的查询词，或改用 searchNotes 在笔记中搜索";
            case "listNotes" -> "可能需要调整查询数量，或检查笔记是否存在";
            case "createNote", "editNote", "appendNote" -> "检查参数是否完整，标题和内容是否正确";
            default -> "检查参数或尝试其他工具";
        };
    }

    // ============================================================
    // 反思层
    // ============================================================

    /**
     * 构建重规划上下文（替代模糊的"请反思"提示）。
     *
     * 把失败结果转成可执行的新策略：告诉模型上一动作是什么、为什么没推进、
     * 禁止做什么、可以尝试什么替代方案。
     */
    private String buildReflectionPrompt(AgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("[系统：重规划]\n\n");

        // 找到最近的非 GOOD 调用
        AgentState.ToolCallRecord lastFailed = null;
        for (int i = state.getToolHistory().size() - 1; i >= 0; i--) {
            AgentState.ToolCallRecord r = state.getToolHistory().get(i);
            if (r.quality() != ResultQuality.GOOD) {
                lastFailed = r;
                break;
            }
        }

        if (lastFailed != null) {
            sb.append("上一动作：").append(lastFailed.toolName())
                    .append("(\"").append(truncateArgs(lastFailed.args())).append("\")\n");
            sb.append("结果质量：").append(lastFailed.quality()).append("\n");

            if (lastFailed.quality() == ResultQuality.POOR) {
                sb.append("含义：工具执行成功，但返回的结果不足以回答用户问题。\n");
                sb.append("不要重复：相同的工具和参数。\n\n");
            } else {
                sb.append("含义：工具执行失败，该路径不可用。\n");
                sb.append("不要重复：相同的工具和参数。\n\n");
            }

            // 生成候选替代策略
            List<String> candidates = generateCandidateStrategies(
                    lastFailed.toolName(), lastFailed.args(), state);
            if (!candidates.isEmpty()) {
                sb.append("可选替代策略（至少选一个不同方向）：\n");
                for (int i = 0; i < candidates.size(); i++) {
                    sb.append("  ").append(i + 1).append(". ").append(candidates.get(i)).append("\n");
                }
                sb.append("\n");
            }
        }

        // 汇总已尝试但失败的动作
        if (!state.getFailedActions().isEmpty()) {
            sb.append("已尝试过的失败动作（禁止重复）：\n");
            for (String fa : state.getFailedActions()) {
                sb.append("  - ").append(fa).append("\n");
            }
            sb.append("\n");
        }

        if (!state.getWorkingMemory().isEmpty()) {
            sb.append("已知事实：\n");
            for (String fact : state.getWorkingMemory()) {
                sb.append("  - ").append(fact).append("\n");
            }
            sb.append("\n");
        }

        sb.append("请选择一个与之前不同的策略继续执行。不要直接回答用户。");
        return sb.toString();
    }

    /**
     * 为 POOR 或 ERROR 的工具调用生成候选替代策略。
     *
     * 核心原则：换策略不只是换参数，也包括换工具。
     * 例如 searchNotes POOR → 可以 listNotes、可以换关键词、可以搜索相关概念。
     */
    private List<String> generateCandidateStrategies(
            String failedTool, String failedArgs, AgentState state) {

        List<String> candidates = new ArrayList<>();
        String query = extractQueryArg(failedArgs);

        switch (failedTool) {
            case "searchNotes" -> {
                // 策略 1：换更通用的关键词
                if (query != null && query.length() > 3) {
                    candidates.add("searchNotes 换更通用关键词（如去掉修饰词）");
                }
                // 策略 2：搜索相关概念
                if (query != null && query.length() >= 2) {
                    candidates.add("searchNotes 搜索相关概念或同义词");
                }
                // 策略 3：改用 listNotes 浏览全部笔记
                candidates.add("listNotes 浏览全部笔记，从中找相关标题");
                // 策略 4：如果还没试过 getRecentNotes
                if (!state.hasCalledTool("getRecentNotes")) {
                    candidates.add("getRecentNotes 获取最近编辑的笔记");
                }
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
                candidates.add("listNotes 浏览笔记目录查找相关笔记");
            }
            default -> {
                candidates.add("searchNotes 搜索相关笔记");
                candidates.add("listNotes 浏览全部笔记");
            }
        }

        return candidates;
    }

    /**
     * 从工具参数的 JSON 字符串中提取 query 字段
     */
    private String extractQueryArg(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return null;
        try {
            Map<String, String> args = AgentService.parseToolArguments(argsJson);
            return args.getOrDefault("query",
                    args.getOrDefault("noteId",
                            args.getOrDefault("title", null)));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 截断过长的参数字符串，只保留关键信息
     */
    private String truncateArgs(String args) {
        if (args == null) return "";
        if (args.length() <= 60) return args;
        return args.substring(0, 57) + "...";
    }

    /**
     * 构建重复调用拦截消息
     */
    private String buildDuplicateBlockMessage(String toolName, String args, AgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("[系统：调用被拦截]\n");
        sb.append("工具 ").append(toolName).append(" 使用相同参数已经调用过并失败了。\n");
        sb.append("请不要再重复相同的调用。\n\n");

        // 给出替代建议
        String alternative = evaluator.suggestFixByErrorCode(toolName, args,
                inferErrorCodeFromHistory(toolName, args, state));
        sb.append("建议：").append(alternative).append("\n");

        if (!state.getWorkingMemory().isEmpty()) {
            sb.append("\n已知事实：\n");
            for (String fact : state.getWorkingMemory()) {
                sb.append("  - ").append(fact).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 构建丰富的重试提示（CompletionGate 拦截时使用）
     */
    private String buildRichRetryPrompt(CompletionGate.GateResult gate, AgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("[系统：任务尚未完成]\n\n");
        sb.append(gate.retryPrompt()).append("\n\n");

        // 附加状态上下文
        String summary = state.buildStateSummary();
        if (!summary.isEmpty()) {
            sb.append(summary);
        }

        return sb.toString();
    }

    // ============================================================
    // 工作记忆提取
    // ============================================================

    /**
     * 更新会话上下文（当前活跃笔记追踪）
     */
    private void updateSessionContext(String sessionId, String toolName, String toolArgs, String rawResult) {
        if (sessionId == null) return;
        try {
            ConversationContext ctx = contextManager.getOrCreate(sessionId);
            ctx.updateFromToolCall(toolName, toolArgs, rawResult);
        } catch (Exception e) {
            log.debug("更新会话上下文失败: {}", e.getMessage());
        }
    }

    /**
     * 判断是否为写操作工具
     */
    private boolean isWriteTool(String toolName) {
        return "createNote".equals(toolName) || "editNote".equals(toolName)
                || "appendNote".equals(toolName) || "deleteNote".equals(toolName)
                || "mergeNotes".equals(toolName) || "markReviewed".equals(toolName)
                || "scheduleReview".equals(toolName);
    }

    /**
     * 根据工具类型升级证据级别
     */
    private void upgradeEvidenceFromTool(String toolName, AgentState state) {
        switch (toolName) {
            case "generateMindMap", "generateDiagram" -> state.upgradeEvidence(AgentState.EvidenceLevel.ARTIFACT_EVIDENCE);
            case "getNote" -> state.upgradeEvidence(AgentState.EvidenceLevel.CONTENT_EVIDENCE);
            case "searchNotes" -> state.upgradeEvidence(AgentState.EvidenceLevel.SEARCH_EVIDENCE);
            case "listNotes" -> state.upgradeEvidence(AgentState.EvidenceLevel.LIST_EVIDENCE);
        }
    }

    /**
     * 从成功的工具调用中提取关键事实，写入工作记忆
     */
    private void extractFactsFromSuccess(String toolName, String args, String result, AgentState state) {
        switch (toolName) {
            case "generateMindMap" -> state.addKnownFact("已成功生成思维导图，任务完成");
            case "generateDiagram" -> state.addKnownFact("已成功生成图表，任务完成");
            case "searchNotes" -> {
                // 提取搜索到的笔记 ID 列表
                if (result.contains("[ID:")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[ID: ([^\\]]+)\\]")
                            .matcher(result);
                    List<String> ids = new ArrayList<>();
                    while (m.find()) {
                        ids.add(m.group(1));
                    }
                    if (!ids.isEmpty()) {
                        state.addKnownFact("searchNotes 找到了 " + ids.size() + " 篇笔记，ID: " + String.join(", ", ids));
                    }
                }
            }
            case "listNotes" -> {
                if (result.contains("[ID:")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[ID: ([^\\]]+)\\]")
                            .matcher(result);
                    List<String> ids = new ArrayList<>();
                    while (m.find()) {
                        ids.add(m.group(1));
                    }
                    if (!ids.isEmpty()) {
                        state.addKnownFact("listNotes 列出了 " + ids.size() + " 篇笔记");
                    }
                }
            }
            case "getNote" -> {
                // 提取标题
                java.util.regex.Matcher titleMatch = java.util.regex.Pattern.compile("^# (.+)$", java.util.regex.Pattern.MULTILINE)
                        .matcher(result);
                if (titleMatch.find()) {
                    state.addKnownFact("已成功读取笔记《" + titleMatch.group(1) + "》的完整内容");
                }
            }
            case "getNoteStats" -> {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("总计 (\\d+) 条笔记").matcher(result);
                if (m.find()) {
                    state.addKnownFact("用户共有 " + m.group(1) + " 条笔记");
                }
            }
        }
    }

    /**
     * 从历史记录中推断上次失败的错误码
     */
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

    private String buildForceToolArgs(String toolName, String toolDesc, String userQuery) {
        return switch (toolName) {
            case "listNotes" -> "{\"count\":\"20\",\"category\":\"\"}";
            case "getNote" -> "{\"noteId\":\"" + escapeJson(toolDesc) + "\"}";
            case "createNote" -> {
                String title = toolDesc != null && !toolDesc.isBlank() ? toolDesc : userQuery;
                if (title.length() > 50) title = title.substring(0, 50);
                yield "{\"title\":\"" + escapeJson(title) + "\",\"content\":\"# " + escapeJson(title) + "\\n\\n（内容待补充）\"}";
            }
            case "searchNotes" -> "{\"query\":\"" + escapeJson(toolDesc != null ? toolDesc : userQuery) + "\"}";
            case "ragSummary" -> "{\"query\":\"" + escapeJson(toolDesc != null ? toolDesc : userQuery) + "\"}";
            case "editNote" -> "{\"noteId\":\"" + escapeJson(toolDesc) + "\",\"title\":\"\",\"content\":\"\"}";
            case "appendNote" -> "{\"noteId\":\"" + escapeJson(toolDesc) + "\",\"appendContent\":\"\"}";
            case "deleteNote" -> "{\"noteId\":\"" + escapeJson(toolDesc) + "\"}";
            case "getNoteStats" -> "{}";
            case "getTodayReviews" -> "{}";
            case "getRecentNotes" -> "{\"count\":\"3\"}";
            case "mergeNotes" -> "{\"noteIds\":\"" + escapeJson(toolDesc) + "\",\"newTitle\":\"合并笔记\"}";
            case "fetchUrl" -> "{\"url\":\"" + escapeJson(toolDesc) + "\"}";
            case "scheduleReview" -> "{\"noteId\":\"" + escapeJson(toolDesc) + "\",\"days\":\"3\"}";
            default -> "{}";
        };
    }

    private String forceExecuteTool(String toolName, String toolDesc, String userQuery,
                                      List<ChatMessage> messages, java.util.Set<String> forcedTools,
                                      SseEmitter emitter, String userId) throws IOException {
        if (forcedTools.contains(toolName)) {
            log.info("跳过强制执行 {}，之前已执行过", toolName);
            return null;
        }
        log.info("强制执行工具: {}", toolName);
        sendSseEvent(emitter, "thinking", Map.of(
                "stage", "tool_call",
                "content", "正在调用工具: " + toolName
        ));
        String forcedArgs = buildForceToolArgs(toolName, toolDesc, userQuery);
        String forcedResult = executeTool(toolName, forcedArgs, userId);
        ToolExecutionRequest forcedReq = ToolExecutionRequest.builder()
                .name(toolName).arguments(forcedArgs)
                .id("forced_" + System.currentTimeMillis()).build();
        messages.add(AiMessage.from(List.of(forcedReq)));
        messages.add(ToolExecutionResultMessage.from(forcedReq, forcedResult));
        forcedTools.add(toolName);
        log.info("强制工具 {} 结果 (前100字): {}", toolName,
                forcedResult.length() > 100 ? forcedResult.substring(0, 100) + "..." : forcedResult);
        return forcedResult;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private String executeTool(String toolName, String arguments, String userId) {
        try {
            Map<String, String> args = AgentService.parseToolArguments(arguments);
            String result = switch (toolName) {
                case "listNotes" -> agentTools.listNotes(
                        args.getOrDefault("count", "20"),
                        args.getOrDefault("category", ""),
                        userId);
                case "getNote" -> agentTools.getNote(
                        args.getOrDefault("noteId", ""),
                        userId);
                case "ragSummary" -> agentTools.ragSummary(args.getOrDefault("query", ""), userId);
                case "searchNotes" -> agentTools.searchNotes(args.getOrDefault("query", ""), userId);
                case "getNoteStats" -> agentTools.getNoteStats(userId);
                case "getRecentNotes" -> agentTools.getRecentNotes(
                        args.getOrDefault("count", "3"), userId);
                case "getTodayReviews" -> agentTools.getTodayReviews(userId);
                case "markReviewed" -> agentTools.markReviewed(args.getOrDefault("noteId", ""), userId);
                case "createNote" -> agentTools.createNote(
                        args.getOrDefault("title", ""), args.getOrDefault("content", ""), userId);
                case "editNote" -> agentTools.editNote(
                        args.getOrDefault("noteId", ""),
                        args.getOrDefault("title", ""),
                        args.getOrDefault("content", ""),
                        userId);
                case "appendNote" -> agentTools.appendNote(
                        args.getOrDefault("noteId", ""),
                        args.getOrDefault("appendContent", ""),
                        userId);
                case "deleteNote" -> agentTools.deleteNote(args.getOrDefault("noteId", ""), userId);
                case "getRelatedNotes" -> agentTools.getRelatedNotes(args.getOrDefault("noteId", ""), userId);
                case "mergeNotes" -> agentTools.mergeNotes(
                        args.getOrDefault("noteIds", ""),
                        args.getOrDefault("newTitle", "合并笔记"),
                        userId);
                case "fetchUrl" -> agentTools.fetchUrl(args.getOrDefault("url", ""));
                case "generateDiagram" -> agentTools.generateDiagram(
                        args.getOrDefault("type", "flowchart"),
                        args.getOrDefault("description", ""));
                case "scheduleReview" -> agentTools.scheduleReview(
                        args.getOrDefault("noteId", ""),
                        args.getOrDefault("days", "1"),
                        userId);
                case "generateMindMap" -> agentTools.generateMindMap(
                        args.getOrDefault("noteId", ""), userId);
                case "whatTimeIsNow" -> agentTools.whatTimeIsNow();
                default -> "未知工具: " + toolName;
            };
            return result;
        } catch (Exception e) {
            log.error("工具 '{}' 执行失败: {}", toolName, e.getMessage());
            agentTools.setResult(ToolResult.error("工具执行失败: " + e.getMessage(), "EXCEPTION", true));
            return "工具执行失败: " + e.getMessage();
        }
    }

    private ToolResult getLastToolResult() {
        try {
            return agentTools.getLastResult();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 目标达成时的最终 Observation：明确告诉 LLM"任务已完成，不要再调工具"。
     */
    private String buildGoalAchievedObservation(String toolName, String rawResult,
                                                 GoalEvaluator.GoalEvaluation goalEval, AgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append(rawResult);
        sb.append("\n\n---\n");
        sb.append("[目标已达成]\n");
        sb.append("状态：任务完成\n");
        if (goalEval.message() != null) {
            sb.append("原因：").append(goalEval.message()).append("\n");
        }
        sb.append("已产出：").append(String.join(", ", state.getProducedArtifacts())).append("\n");
        sb.append("目标：").append(state.getGoalDescription()).append("\n\n");
        sb.append("请基于以上工具结果直接回答用户，不要再调用任何工具。");
        return sb.toString();
    }

    /**
     * 根据单个工具名推断需要的 artifact 列表。
     * 用于 Supervisor 没有明确给出 goal 时的自动推断。
     */
    private List<String> inferArtifactsForTool(String toolName) {
        return switch (toolName) {
            case "generateMindMap" -> List.of("mindmap");
            case "generateDiagram" -> List.of("diagram");
            case "createNote" -> List.of("note_created");
            case "editNote", "appendNote" -> List.of("note_updated");
            case "deleteNote" -> List.of("note_deleted");
            case "mergeNotes" -> List.of("notes_merged");
            case "getNote" -> List.of("note_content");
            case "searchNotes" -> List.of("search_results");
            case "listNotes" -> List.of("note_list");
            default -> List.of(toolName + "_done");
        };
    }

    private void sendSseEvent(SseEmitter emitter, String type, Map<String, Object> data) throws IOException {
        if (emitter == null) return;
        Map<String, Object> event = new java.util.HashMap<>();
        event.put("type", type);
        event.putAll(data);
        try {
            emitter.send(SseEmitter.event().data(event));
        } catch (IllegalStateException e) {
            log.debug("SSE send skipped (emitter completed): type={}", type);
        } catch (IOException e) {
            log.debug("SSE send skipped (client disconnected): type={}, msg={}", type, e.getMessage());
        }
    }
}
