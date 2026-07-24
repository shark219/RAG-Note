package com.rag.notebook.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 目标评估器——判断"目标是否达成"，而非"拦截错误"。
 *
 * 与旧 CompletionGate 的本质区别：
 *   CompletionGate：LLM 想停 → 检查是否允许停（事后堵）
 *   GoalEvaluator：  每次动作后 → 判断目标是否满足（事前判）
 *
 * 输入：goal + successCriteria + 当前 AgentState（artifacts, observations, resources）
 * 输出：ACHIEVED → 直接结束 / IN_PROGRESS → 继续，附进度 / BLOCKED → 拦截 LLM 直接回答
 */
@Slf4j
@Component
public class GoalEvaluator {

    /** 工具 → artifact 类型映射（用于 successCriteria 匹配） */
    private static final Map<String, String> TOOL_CRITERION_MAP = Map.ofEntries(
            Map.entry("generateMindMap", "mindmap"),
            Map.entry("generateDiagram", "diagram"),
            Map.entry("createNote", "note_created"),
            Map.entry("editNote", "note_updated"),
            Map.entry("appendNote", "note_updated"),
            Map.entry("deleteNote", "note_deleted"),
            Map.entry("mergeNotes", "notes_merged"),
            Map.entry("markReviewed", "review_marked"),
            Map.entry("scheduleReview", "review_scheduled"),
            Map.entry("getNote", "note_content"),
            Map.entry("searchNotes", "search_results"),
            Map.entry("ragSummary", "kb_summary")
    );

    /** 产生可见产物的工具（思维导图、图表等 → 前端渲染） */
    private static final Set<String> VISIBLE_ARTIFACT_TOOLS = Set.of(
            "generateMindMap", "generateDiagram"
    );

    /** 写操作工具：成功即任务完成 */
    private static final Set<String> WRITE_TOOLS = Set.of(
            "createNote", "editNote", "appendNote", "deleteNote",
            "mergeNotes", "markReviewed", "scheduleReview"
    );

    // ============================================================
    // 工具成功后评估
    // ============================================================

    /**
     * 每次工具成功执行后立即调用。
     *
     * @return ACHIEVED → AgentLoop 直接结束，IN_PROGRESS → 继续（附带进度信息）
     */
    public GoalEvaluation evaluateAfterToolSuccess(AgentState state, String toolName, String rawResult) {

        // 1. 构建 Observation
        Observation obs = buildObservation(toolName, rawResult);
        state.addObservation(obs);

        // 2. 注册成功步骤（匹配 successCriteria）
        String criterion = TOOL_CRITERION_MAP.get(toolName);
        if (criterion != null) {
            state.markStepCompleted(criterion);
        }

        // 3. 产生可见产物（思维导图、图表 → 前端渲染）
        if (VISIBLE_ARTIFACT_TOOLS.contains(toolName)) {
            Artifact artifact = buildArtifact(toolName, criterion, rawResult);
            state.addArtifact(artifact);
        }

        // 4. 写操作成功 → 注册确认
        if (WRITE_TOOLS.contains(toolName)) {
            state.markWriteConfirmation(toolName + " 执行成功");
        }

        // 5. 有明确 successCriteria → 逐条检查，全部满足才达成
        if (state.hasGoal() && !state.getSuccessCriteria().isEmpty()) {
            EvaluationResult result = checkSuccessCriteria(state);
            if (result.allMet) {
                state.setTaskStatus(TaskStatus.GOAL_ACHIEVED);
                log.info("GoalEvaluator: 所有 successCriteria 已满足 ({}/{}) → 目标达成",
                        result.met, result.total);
                return GoalEvaluation.achieved(state, "所有成功标准已满足: " + String.join(", ", result.metList));
            }
            log.info("GoalEvaluator: 进度 {}/{}，还缺 {}",
                    result.met, result.total, result.missing);
            return GoalEvaluation.inProgress(state, result);
        }

        // 6. 有目标但无标准，或自由对话 → 继续
        return GoalEvaluation.undetermined(state);
    }

    // ============================================================
    // LLM 返回文本时评估
    // ============================================================

    /**
     * LLM 想直接回答（不调工具）时调用。
     *
     * @return BLOCKED → 拦截（注入重试提示），ALLOWED → 放行
     */
    public GoalEvaluation evaluateOnTextResponse(AgentState state, String llmAnswer) {

        // 目标已达成 → 放行
        if (state.isGoalAchieved()) {
            return GoalEvaluation.allowed(state);
        }

        // 有 successCriteria → 必须全部满足才放行
        if (state.hasGoal() && !state.getSuccessCriteria().isEmpty()) {
            EvaluationResult result = checkSuccessCriteria(state);
            if (result.allMet) {
                state.setTaskStatus(TaskStatus.GOAL_ACHIEVED);
                return GoalEvaluation.allowed(state);
            }
            return GoalEvaluation.blocked(state, buildCriteriaNotMetPrompt(state, result));
        }

        // 写操作已确认且无 successCriteria → 放行
        if (state.hasWriteConfirmation()) {
            state.setTaskStatus(TaskStatus.GOAL_ACHIEVED);
            return GoalEvaluation.allowed(state);
        }

        // 有目标但无任何工具调用 → 拦截
        if (state.hasGoal() && !state.hasSuccessfulToolCall()) {
            return GoalEvaluation.blocked(state, buildMustCallToolPrompt(state));
        }

        // 有工具调用但全是 POOR → 拦截
        if (state.hasSuccessfulToolCall() && !state.hasGoodQualityResult()) {
            return GoalEvaluation.blocked(state, buildPoorQualityPrompt(state));
        }

        return GoalEvaluation.allowed(state);
    }

    // ============================================================
    // successCriteria 检查
    // ============================================================

    private EvaluationResult checkSuccessCriteria(AgentState state) {
        List<String> met = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String criterion : state.getSuccessCriteria()) {
            if (isCriterionMet(criterion, state)) {
                met.add(criterion);
            } else {
                missing.add(criterion);
            }
        }

        return new EvaluationResult(
                met.size() == state.getSuccessCriteria().size(),
                met.size(),
                state.getSuccessCriteria().size(),
                met,
                missing
        );
    }

    /**
     * 判断单条 successCriteria 是否满足。
     *
     * 匹配规则：
     *   - artifact 名称匹配（如 "mindmap" 匹配 artifact.type == "mindmap"）
     *   - completedSteps 包含该步骤
     *   - workingMemory 包含该事实
     */
    private boolean isCriterionMet(String criterion, AgentState state) {
        // 直接匹配 artifact 类型
        if (state.hasArtifactOfType(criterion)) return true;

        // 匹配 completedSteps
        if (state.getCompletedSteps().contains(criterion)) return true;

        // 模糊匹配 workingMemory（含有关键词）
        for (String fact : state.getWorkingMemory()) {
            if (fact.contains(criterion)) return true;
        }

        return false;
    }

    // ============================================================
    // Observation / Artifact 构建
    // ============================================================

    private Observation buildObservation(String toolName, String rawResult) {
        String summary = rawResult != null && rawResult.length() > 100
                ? rawResult.substring(0, 100) + "..."
                : rawResult;
        Map<String, Object> data = new HashMap<>();
        data.put("raw", rawResult);

        // 提取结构化信息
        if (rawResult != null) {
            if (rawResult.contains("失败") || rawResult.contains("错误") || rawResult.contains("异常")) {
                return new Observation(toolName, "error", summary, data);
            }
            if (rawResult.contains("未找到") || rawResult.contains("没有找到") || rawResult.contains("无相关")) {
                return new Observation(toolName, "empty", summary, data);
            }
        }
        return new Observation(toolName, "success", summary, data);
    }

    private Artifact buildArtifact(String toolName, String artifactType, String rawResult) {
        String label = extractLabel(toolName, rawResult);
        Map<String, Object> meta = new HashMap<>();
        meta.put("tool", toolName);
        // 存储产物内容（前端需要渲染），截断避免过大
        if (rawResult != null && !rawResult.isBlank()) {
            int maxLen = 50000;
            meta.put("content", rawResult.length() > maxLen ? rawResult.substring(0, maxLen) + "\n...(truncated)" : rawResult);
        }
        return new Artifact(artifactType, UUID.randomUUID().toString().substring(0, 8), label, meta);
    }

    private String extractLabel(String toolName, String rawResult) {
        if (rawResult == null) return toolName;
        // 尝试提取标题
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("标题[：:]\\s*(.+)")
                .matcher(rawResult);
        if (m.find()) return m.group(1).trim();
        // 思维导图/图表
        if ("generateMindMap".equals(toolName)) return "思维导图";
        if ("generateDiagram".equals(toolName)) return "图表";
        return toolName;
    }

    // ============================================================
    // 提示构建（替代旧的 CompletionGate retryPrompt）
    // ============================================================

    private String buildMustCallToolPrompt(AgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("[环境状态]\n");
        sb.append("目标：").append(state.getGoal()).append("\n");
        sb.append("当前状态：尚未调用任何工具，无法验证目标是否可达。\n\n");
        sb.append("你必须调用合适的工具来推进目标。不要直接回答。");
        if (!state.getResources().isEmpty()) {
            sb.append("\n\n可用资源：\n");
            for (Resource r : state.getResources()) {
                sb.append("  - ").append(r.type()).append(": ").append(r.label()).append("\n");
            }
        }
        return sb.toString();
    }

    private String buildCriteriaNotMetPrompt(AgentState state, EvaluationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("[环境状态]\n");
        sb.append("目标：").append(state.getGoal()).append("\n");
        sb.append("进度：").append(result.met).append("/").append(result.total).append(" 已满足\n");

        sb.append("已满足：\n");
        for (String m : result.metList) sb.append("  [✓] ").append(m).append("\n");
        sb.append("未满足：\n");
        for (String m : result.missing) sb.append("  [ ] ").append(m).append("\n");

        sb.append("\n目标尚未达成。请继续调用工具完成缺失的部分，不要直接回答。");
        return sb.toString();
    }

    private String buildPoorQualityPrompt(AgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("[环境状态]\n");
        sb.append("工具已执行但返回结果质量不足。\n\n");
        sb.append("最近观察：\n");
        for (int i = Math.max(0, state.getObservations().size() - 3); i < state.getObservations().size(); i++) {
            Observation o = state.getObservations().get(i);
            sb.append("  - ").append(o.action()).append(" → ").append(o.status())
                    .append("（").append(o.summary()).append("）\n");
        }
        sb.append("\n请换一种策略获取数据，不要重复刚才的动作，不要直接回答。");
        return sb.toString();
    }

    // ============================================================
    // 内部类型
    // ============================================================

    private record EvaluationResult(
            boolean allMet, int met, int total,
            List<String> metList, List<String> missing
    ) {}

    public enum GoalStatus { ACHIEVED, IN_PROGRESS, BLOCKED, ALLOWED, UNDETERMINED }

    public record GoalEvaluation(
            GoalStatus status,
            String message,
            String progressPrompt,
            AgentState snapshot
    ) {
        public boolean isAchieved() { return status == GoalStatus.ACHIEVED || status == GoalStatus.ALLOWED; }
        public boolean isBlocked() { return status == GoalStatus.BLOCKED; }

        static GoalEvaluation achieved(AgentState state, String msg) {
            return new GoalEvaluation(GoalStatus.ACHIEVED, msg,
                    "[目标已达成] " + msg + "。请基于以上结果直接回答用户，不要再调用任何工具。", state);
        }

        static GoalEvaluation inProgress(AgentState state, EvaluationResult result) {
            StringBuilder prompt = new StringBuilder();
            prompt.append("[目标进度] ").append(result.met).append("/").append(result.total).append("\n");
            prompt.append("已满足：").append(String.join(", ", result.metList)).append("\n");
            prompt.append("还缺：").append(String.join(", ", result.missing)).append("\n");
            prompt.append("请继续调用合适的工具来满足缺失的标准。");
            return new GoalEvaluation(GoalStatus.IN_PROGRESS,
                    "进行中: " + result.met + "/" + result.total, prompt.toString(), state);
        }

        static GoalEvaluation blocked(AgentState state, String prompt) {
            return new GoalEvaluation(GoalStatus.BLOCKED, "目标未达成", prompt, state);
        }

        static GoalEvaluation allowed(AgentState state) {
            return new GoalEvaluation(GoalStatus.ALLOWED, "允许通过", null, state);
        }

        static GoalEvaluation undetermined(AgentState state) {
            return new GoalEvaluation(GoalStatus.UNDETERMINED, "无明确目标", null, state);
        }
    }
}
