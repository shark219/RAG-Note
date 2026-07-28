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

    public AgentLoop(AgentTools agentTools, ToolResultEvaluator evaluator,
                     ModelFactory modelFactory, GoalEvaluator goalEvaluator,
                     ConversationContextManager contextManager) {
        this.agentTools = agentTools;
        this.evaluator = evaluator;
        this.modelFactory = modelFactory;
        this.goalEvaluator = goalEvaluator;
        this.contextManager = contextManager;
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

        // 设置目标
        if (goal != null && !goal.isBlank()) {
            state.setGoal(goal);
            state.setSuccessCriteria(successCriteria);
            log.info("目标: goal={}, successCriteria={}", goal, successCriteria);
        } else {
            state.setTaskStatus(TaskStatus.EXECUTING);
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.addAll(historyMessages);
        messages.add(UserMessage.from(userQuery));

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.info("Agent Loop 第 {} 轮", i + 1);

            // 连续无进展 → 注入环境状态让 LLM 重新审视
            if (state.needsReflection()) {
                String stateView = buildStateViewForReflection(state);
                messages.add(UserMessage.from(stateView));
                state.markProgress();
                log.info("注入环境状态视图（连续{}轮无进展）:\n{}",
                        state.getConsecutiveNoProgress() + 1, stateView);
            }

            // 每轮开始时发送思考状态，包含轮次和进度信息
            sendRoundThinking(emitter, i + 1, state);

            ChatLanguageModel llm = modelFactory.createPreciseModel();
            Response<AiMessage> response = llm.generate(messages, activeTools);
            AiMessage aiMessage = response.content();

            if (aiMessage.hasToolExecutionRequests()) {
                messages.add(aiMessage);
                boolean anyProgress = false;

                // 打印 LLM 本轮决策
                List<String> toolNames = aiMessage.toolExecutionRequests().stream()
                        .map(ToolExecutionRequest::name).toList();
                log.info("LLM 决定调用工具: {} (第{}轮)", toolNames, i + 1);

                int totalTools = aiMessage.toolExecutionRequests().size();
                int toolIndex = 0;
                for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                    toolIndex++;
                    String toolName = req.name();
                    String toolArgs = req.arguments();

                    sendSseEvent(emitter, "thinking", Map.of(
                            "stage", "tool_call",
                            "round", i + 1,
                            "content", "正在调用工具: " + toolName,
                            "tool_index", toolIndex,
                            "tool_total", totalTools
                    ));

                    // 防重复规则 1：同工具同参数已失败过
                    if (state.hasCalledWithArgsAndFailed(toolName, toolArgs)) {
                        log.info("拦截重复调用: {}({})，之前已失败", toolName, toolArgs);
                        messages.add(ToolExecutionResultMessage.from(req,
                                buildStateAwareBlockMessage(toolName, toolArgs, state)));
                        continue;
                    }

                    // 防重复规则 2：刚执行过完全相同的调用
                    if (state.hasSameAction(toolName, toolArgs)) {
                        log.info("拦截机械重复: {}({})，与上一轮完全相同", toolName, toolArgs);
                        messages.add(ToolExecutionResultMessage.from(req,
                                "[环境状态] 该工具和参数刚刚已尝试过，没有推进任务。\n"
                                + state.buildStateSummary() + "\n"
                                + "请分析当前状态并选择不同策略。"));
                        continue;
                    }

                    // 执行工具
                    String rawResult = executeTool(toolName, toolArgs, userId);
                    ToolResult toolResult = getLastToolResult();

                    // 评估并记录
                    ToolResultEvaluator.Evaluation eval = evaluator.evaluate(
                            toolName, toolArgs, rawResult, state.getOriginalQuery(), toolResult);
                    state.recordToolCall(toolName, toolArgs, rawResult, eval.quality());

                    // 构建 Observation 注入给 LLM
                    String observationMsg = buildObservationMessage(
                            toolName, toolArgs, rawResult, eval, toolResult, state);
                    messages.add(ToolExecutionResultMessage.from(req, observationMsg));

                    log.info("工具 {} 参数={} 结果: quality={}, 内容预览: {}",
                            toolName, truncateArgs(toolArgs), eval.quality(),
                            rawResult != null && rawResult.length() > 150
                                    ? rawResult.substring(0, 150).replace("\n", "\\n") + "..."
                                    : rawResult != null ? rawResult.replace("\n", "\\n") : "(null)");

                    if (eval.quality() == ResultQuality.GOOD) {
                        state.markProgress();
                        anyProgress = true;
                        upgradeEvidenceFromTool(toolName, state);
                        extractFactsFromSuccess(toolName, toolArgs, rawResult, state);
                        updateSessionContext(sessionId, toolName, toolArgs, rawResult);

                        // ===== GoalEvaluator：目标是否已达成？ =====
                        GoalEvaluator.GoalEvaluation goalEval = goalEvaluator.evaluateAfterToolSuccess(
                                state, toolName, rawResult);
                        if (goalEval.isAchieved()) {
                            log.info("GoalEvaluator: 目标已达成 → 工具 {} 成功后直接结束 (第{}轮)",
                                    toolName, i + 1);
                            // 将目标达成信号注入最后一条消息
                            String finalMsg = rawResult + "\n\n---\n[目标已达成]\n"
                                    + goalEval.message() + "\n"
                                    + "请基于以上结果直接回答用户，不要再调用任何工具。";
                            messages.set(messages.size() - 1,
                                    ToolExecutionResultMessage.from(req, finalMsg));
                            return AgentLoopResult.ready(state);
                        }
                        // 目标未达成 → 注入进度信息
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
                    } else {
                        state.markNoProgress();
                        evaluator.buildStructuredReflection(toolName, toolArgs, eval, state);
                    }
                }

                if (!anyProgress) state.markNoProgress();

            } else {
                // LLM 返回文本，未调用工具
                String answer = aiMessage.text();
                log.info("LLM 返回文本（第{}轮，不调工具），内容预览: {}",
                        i + 1, answer.length() > 80 ? answer.substring(0, 80) + "..." : answer);

                // 第1轮无工具调用且回复为反问 → 需要用户澄清意图
                if (i == 0 && !state.hasSuccessfulToolCall() && isClarificationQuestion(answer)) {
                    log.info("Agent 识别为反问澄清: {}", answer.length() > 80 ? answer.substring(0, 80) + "..." : answer);
                    return AgentLoopResult.needClarification(state, answer);
                }

                // GoalEvaluator 判断是否放行
                GoalEvaluator.GoalEvaluation goalEval = goalEvaluator.evaluateOnTextResponse(state, answer);
                if (goalEval.isBlocked()) {
                    log.info("GoalEvaluator 拦截 LLM 回答（第{}轮）: {}",
                            i + 1, goalEval.message());
                    state.markNoProgress();
                    messages.add(UserMessage.from(goalEval.progressPrompt()));
                    continue;
                }

                log.info("Agent Loop 完成，共 {} 轮", i + 1);
                return AgentLoopResult.ready(state);
            }
        }

        log.info("Agent Loop 达到最大轮次 {}", MAX_ITERATIONS);
        return AgentLoopResult.maxRounds(state);
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

    private String executeTool(String toolName, String arguments, String userId) {
        try {
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
}
