package com.rag.notebook.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 执行状态：记录工具调用历史、工作记忆和反思信息
 *
 * 核心设计：状态不仅记录"做了什么"，还记录"学到了什么"，
 * 让模型在每轮决策时能看到不断增长的 knownFacts 和 failedActions。
 *
 * 证据级别：区分不同深度的工具结果，让 CompletionGate 做出更智能的判断。
 *   LIST_EVIDENCE    — 只拿到了标题和摘要（listNotes）
 *   SEARCH_EVIDENCE  — 拿到了匹配内容和预览（searchNotes）
 *   CONTENT_EVIDENCE — 拿到了完整笔记正文（getNote）
 */
public class AgentState {

    private final String originalQuery;
    private final List<ToolCallRecord> toolHistory = new ArrayList<>();
    private String bestAnswer;

    // 任务约束（由 Supervisor 设置）
    private String requiredTool;
    private boolean toolRequired;
    private String taskGoal;

    // 工作记忆：执行过程中积累的事实（如"UI自动化不是有效noteId"）
    private final List<String> workingMemory = new ArrayList<>();

    // 失败动作记录：已执行且失败的工具+参数描述
    private final List<String> failedActions = new ArrayList<>();

    // 连续无进展计数器
    private int consecutiveNoProgress = 0;

    // 当前达到的最高证据级别
    private EvidenceLevel highestEvidence = EvidenceLevel.NONE;

    // 写操作确认：非null表示一次写操作已成功完成
    private String writeConfirmation;

    public AgentState(String originalQuery) {
        this.originalQuery = originalQuery;
    }

    // --- 任务约束 ---

    public void setRequiredTool(String requiredTool) {
        this.requiredTool = requiredTool;
    }

    public String getRequiredTool() {
        return requiredTool;
    }

    public void setToolRequired(boolean toolRequired) {
        this.toolRequired = toolRequired;
    }

    public boolean isToolRequired() {
        return toolRequired;
    }

    public void setTaskGoal(String taskGoal) {
        this.taskGoal = taskGoal;
    }

    public String getTaskGoal() {
        return taskGoal;
    }

    public String getOriginalQuery() {
        return originalQuery;
    }

    public List<ToolCallRecord> getToolHistory() {
        return toolHistory;
    }

    public void recordToolCall(String toolName, String args, String result, ResultQuality quality) {
        toolHistory.add(new ToolCallRecord(toolName, args, result, quality));
    }

    public String getBestAnswer() {
        return bestAnswer;
    }

    public void setBestAnswer(String bestAnswer) {
        this.bestAnswer = bestAnswer;
    }

    // --- 工作记忆 ---

    /**
     * 向工作记忆中添加一条已知事实（如"xxx不是有效noteId"）
     */
    public void addKnownFact(String fact) {
        if (fact != null && !fact.isBlank() && !workingMemory.contains(fact)) {
            workingMemory.add(fact);
        }
    }

    public List<String> getWorkingMemory() {
        return workingMemory;
    }

    /**
     * 记录一个失败的动作描述
     */
    public void addFailedAction(String description) {
        if (description != null && !description.isBlank()) {
            failedActions.add(description);
        }
    }

    public List<String> getFailedActions() {
        return failedActions;
    }

    // --- 进展追踪 ---

    /**
     * 本轮有进展（GOOD 结果），重置计数器
     */
    public void markProgress() {
        consecutiveNoProgress = 0;
    }

    /**
     * 本轮无进展（POOR/ERROR），计数器+1
     */
    public void markNoProgress() {
        consecutiveNoProgress++;
    }

    public int getConsecutiveNoProgress() {
        return consecutiveNoProgress;
    }

    /**
     * 是否连续多轮没有进展，需要触发反思
     */
    public boolean needsReflection() {
        return consecutiveNoProgress >= 2;
    }

    // --- 查询方法 ---

    /**
     * 检查某个工具是否已经调用过
     */
    public boolean hasCalledTool(String toolName) {
        return toolHistory.stream().anyMatch(r -> r.toolName().equals(toolName));
    }

    /**
     * 是否有至少一次工具执行成功（POOR 或 GOOD 都算执行成功，只是质量不同）
     * 区别于 ERROR（工具崩溃/抛异常）。
     *
     * POOR = 工具跑通了，但结果不够好 → 算"已执行"
     * ERROR = 工具崩溃 → 不算
     */
    public boolean hasSuccessfulToolCall() {
        return toolHistory.stream()
                .anyMatch(r -> r.quality() != ResultQuality.ERROR);
    }

    /**
     * 是否有 GOOD 质量的工具结果（数据确实可用）
     */
    public boolean hasGoodQualityResult() {
        return toolHistory.stream()
                .anyMatch(r -> r.quality() == ResultQuality.GOOD);
    }

    /**
     * 上一轮调用是否是 POOR（执行成功但证据不足）
     */
    public boolean lastCallWasPoor() {
        if (toolHistory.isEmpty()) return false;
        return toolHistory.get(toolHistory.size() - 1).quality() == ResultQuality.POOR;
    }

    /**
     * 是否有笔记相关工具的成功调用（向后兼容，等同于 hasEvidence(LIST)）
     */
    public boolean hasNoteEvidence() {
        return hasEvidence(EvidenceLevel.LIST_EVIDENCE);
    }

    // --- 证据级别 ---

    /**
     * 尝试提升证据级别（只升不降）
     */
    public void upgradeEvidence(EvidenceLevel level) {
        if (level.ordinal() > highestEvidence.ordinal()) {
            highestEvidence = level;
        }
    }

    /**
     * 当前证据级别是否满足最低要求
     */
    public boolean hasEvidence(EvidenceLevel required) {
        return highestEvidence.ordinal() >= required.ordinal();
    }

    public EvidenceLevel getHighestEvidence() {
        return highestEvidence;
    }

    // --- 写操作确认 ---

    public String getWriteConfirmation() {
        return writeConfirmation;
    }

    public void markWriteConfirmation(String message) {
        this.writeConfirmation = message;
    }

    public boolean hasWriteConfirmation() {
        return writeConfirmation != null;
    }

    /**
     * 检查某个工具+参数组合是否已经调用过且失败（防止无效重试）
     */
    public boolean hasCalledWithArgsAndFailed(String toolName, String args) {
        return toolHistory.stream()
                .anyMatch(r -> r.toolName().equals(toolName)
                        && r.args() != null && r.args().equals(args)
                        && r.quality() == ResultQuality.ERROR);
    }

    /**
     * 检查是否刚执行过完全相同的工具+参数（无论成败，防止机械重复）
     */
    public boolean hasSameAction(String toolName, String args) {
        if (toolHistory.isEmpty()) return false;
        ToolCallRecord last = toolHistory.get(toolHistory.size() - 1);
        return last.toolName().equals(toolName)
                && last.args() != null && last.args().equals(args);
    }

    /**
     * 获取某个工具的上次调用结果
     */
    public String getLastResult(String toolName) {
        for (int i = toolHistory.size() - 1; i >= 0; i--) {
            if (toolHistory.get(i).toolName().equals(toolName)) {
                return toolHistory.get(i).result();
            }
        }
        return null;
    }

    /**
     * 上一轮调用是否成功（GOOD）
     */
    public boolean lastCallWasGood() {
        if (toolHistory.isEmpty()) return false;
        return toolHistory.get(toolHistory.size() - 1).quality() == ResultQuality.GOOD;
    }

    /**
     * 构建结构化状态摘要（供 Reflection 层和 CompletionGate 使用）
     */
    public String buildStateSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("目标：").append(originalQuery);
        if (taskGoal != null && !taskGoal.isBlank()) {
            sb.append("（").append(taskGoal).append("）");
        }
        sb.append("\n");

        if (!workingMemory.isEmpty()) {
            sb.append("已知事实：\n");
            for (String fact : workingMemory) {
                sb.append("  - ").append(fact).append("\n");
            }
        }

        if (!failedActions.isEmpty()) {
            sb.append("失败动作：\n");
            for (String action : failedActions) {
                sb.append("  - ").append(action).append("\n");
            }
        }

        if (!toolHistory.isEmpty()) {
            sb.append("工具调用历史（最近3轮）：\n");
            int start = Math.max(0, toolHistory.size() - 3);
            for (int i = start; i < toolHistory.size(); i++) {
                ToolCallRecord r = toolHistory.get(i);
                sb.append("  ").append(i + 1).append(". ")
                        .append(r.toolName()).append(" → ").append(r.quality());
                if (r.quality() != ResultQuality.GOOD) {
                    String preview = r.result().length() > 80 ? r.result().substring(0, 80) + "..." : r.result();
                    sb.append("（").append(preview).append("）");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 工具调用记录
     */
    public record ToolCallRecord(String toolName, String args, String result, ResultQuality quality) {}

    /**
     * 工具结果质量（执行维度）
     */
    public enum ResultQuality {
        GOOD,   // 执行成功，返回了数据
        POOR,   // 执行成功但结果不理想（空结果等）
        ERROR   // 执行失败
    }

    /**
     * 证据级别（内容深度维度）
     *
     * 区分工具结果的信息量，让 CompletionGate 根据任务需求做判断：
     * - "我有哪些笔记？" → LIST 就够
     * - "找一下线程池笔记" → SEARCH 就够
     * - "长江三峡那篇写了什么？" → 必须 CONTENT
     */
    public enum EvidenceLevel {
        NONE,             // 无任何证据
        LIST_EVIDENCE,    // 仅有标题和摘要（listNotes）
        SEARCH_EVIDENCE,  // 有匹配内容和预览（searchNotes）
        CONTENT_EVIDENCE  // 有完整笔记正文（getNote）
    }

    /**
     * 任务意图分类，用于 CompletionGate 区分读写语义。
     */
    public enum TaskIntent {
        WRITE_NOTE,
        READ_NOTE,
        SEARCH_NOTE,
        LIST_NOTES,
        KNOWLEDGE_QA,
        STATS,
        REVIEW,
        GENERATE_ARTIFACT,
        GENERAL
    }

    private static final java.util.Set<String> WRITE_TOOLS = java.util.Set.of(
            "createNote", "editNote", "appendNote", "deleteNote", "mergeNotes"
    );

    private static final java.util.Set<String> REVIEW_TOOLS = java.util.Set.of(
            "getTodayReviews", "markReviewed", "scheduleReview"
    );

    private static final java.util.Set<String> ARTIFACT_TOOLS = java.util.Set.of(
            "generateMindMap", "generateDiagram"
    );

    /**
     * 推断当前任务的意图类型。
     * 优先级：requiredTool（Supervisor显式指定） > toolHistory（实际发生的操作） > 默认GENERAL
     */
    public TaskIntent inferIntent() {
        if (requiredTool != null && !requiredTool.isBlank()) {
            if (WRITE_TOOLS.contains(requiredTool)) return TaskIntent.WRITE_NOTE;
            if (REVIEW_TOOLS.contains(requiredTool)) return TaskIntent.REVIEW;
            if (ARTIFACT_TOOLS.contains(requiredTool)) return TaskIntent.GENERATE_ARTIFACT;
            if ("getNote".equals(requiredTool)) return TaskIntent.READ_NOTE;
            if ("searchNotes".equals(requiredTool) || "getRelatedNotes".equals(requiredTool)) return TaskIntent.SEARCH_NOTE;
            if ("listNotes".equals(requiredTool) || "getRecentNotes".equals(requiredTool)) return TaskIntent.LIST_NOTES;
            if ("ragSummary".equals(requiredTool)) return TaskIntent.KNOWLEDGE_QA;
            if ("getNoteStats".equals(requiredTool)) return TaskIntent.STATS;
        }

        for (ToolCallRecord r : toolHistory) {
            if (r.quality() == ResultQuality.GOOD) {
                if (WRITE_TOOLS.contains(r.toolName())) return TaskIntent.WRITE_NOTE;
                if (REVIEW_TOOLS.contains(r.toolName())) return TaskIntent.REVIEW;
                if (ARTIFACT_TOOLS.contains(r.toolName())) return TaskIntent.GENERATE_ARTIFACT;
            }
        }

        return TaskIntent.GENERAL;
    }
}
