package com.rag.notebook.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 执行状态：记录工具调用历史、工作记忆、目标进度和任务状态机。
 *
 * 核心设计：
 *   1. 状态不仅记录"做了什么"，还记录"学到了什么"，
 *      让模型在每轮决策时能看到不断增长的 knownFacts 和 failedActions。
 *   2. 目标追踪：明确知道"要产出什么、已经产出了什么、还缺什么"，
 *      让 GoalEvaluator 在工具成功后立即判断是否该结束，而不是等 LLM 自己猜。
 *
 * 证据级别：区分不同深度的工具结果
 *   LIST_EVIDENCE    — 只拿到了标题和摘要（listNotes）
 *   SEARCH_EVIDENCE  — 拿到了匹配内容和预览（searchNotes）
 *   CONTENT_EVIDENCE — 拿到了完整笔记正文（getNote）
 *   ARTIFACT_EVIDENCE — 已生成产物（generateMindMap, generateDiagram）
 */
public class AgentState {

    private final String originalQuery;
    private final List<ToolCallRecord> toolHistory = new ArrayList<>();
    private String bestAnswer;

    // ========== 任务状态机 ==========
    private TaskStatus taskStatus = TaskStatus.CREATED;

    // ========== 任务约束（由 Supervisor 设置） ==========
    private String requiredTool;
    private boolean toolRequired;
    private String taskGoal;

    // ========== 目标追踪 ==========
    /** 任务目标描述（如"增强古诗词笔记并生成思维导图"） */
    private String goalDescription;
    /** 完成目标需要产出的 artifact 列表（如 ["note_updated", "mindmap"]） */
    private final Set<String> requiredArtifacts = new LinkedHashSet<>();
    /** 已经产出的 artifact 列表 */
    private final Set<String> producedArtifacts = new LinkedHashSet<>();
    /** 目标达成的停止条件描述 */
    private String stopCondition;

    // ========== 工作记忆 ==========
    /** 执行过程中积累的事实（如"UI自动化不是有效noteId"） */
    private final List<String> workingMemory = new ArrayList<>();

    /** 失败动作记录：已执行且失败的工具+参数描述 */
    private final List<String> failedActions = new ArrayList<>();

    /** 连续无进展计数器 */
    private int consecutiveNoProgress = 0;

    /** 当前达到的最高证据级别 */
    private EvidenceLevel highestEvidence = EvidenceLevel.NONE;

    /** 写操作确认：非null表示一次写操作已成功完成 */
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

    // --- 任务状态机 ---

    public TaskStatus getTaskStatus() { return taskStatus; }
    public void setTaskStatus(TaskStatus status) { this.taskStatus = status; }

    public boolean isGoalAchieved() { return taskStatus == TaskStatus.GOAL_ACHIEVED; }

    // --- 目标追踪 ---

    public void setGoalDescription(String goal) { this.goalDescription = goal; }
    public String getGoalDescription() { return goalDescription; }

    public void setRequiredArtifacts(List<String> artifacts) {
        this.requiredArtifacts.clear();
        if (artifacts != null) this.requiredArtifacts.addAll(artifacts);
    }
    public List<String> getRequiredArtifacts() { return new ArrayList<>(requiredArtifacts); }

    public void markArtifactProduced(String artifact) {
        if (artifact != null) {
            producedArtifacts.add(artifact);
            addKnownFact("已产出: " + artifact);
        }
    }
    public List<String> getProducedArtifacts() { return new ArrayList<>(producedArtifacts); }

    public void setStopCondition(String condition) { this.stopCondition = condition; }
    public String getStopCondition() { return stopCondition; }

    /** 是否有明确目标 */
    public boolean hasGoal() {
        return goalDescription != null && !goalDescription.isBlank();
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

        // 目标进度
        if (hasGoal()) {
            sb.append("任务目标：").append(goalDescription).append("\n");
            if (!requiredArtifacts.isEmpty()) {
                sb.append("目标进度：").append(producedArtifacts.size())
                        .append("/").append(requiredArtifacts.size()).append("\n");
                if (!producedArtifacts.isEmpty()) {
                    sb.append("已产出：").append(String.join(", ", producedArtifacts)).append("\n");
                }
                // 计算缺失的
                List<String> missing = new ArrayList<>(requiredArtifacts);
                missing.removeAll(producedArtifacts);
                if (!missing.isEmpty()) {
                    sb.append("还缺：").append(String.join(", ", missing)).append("\n");
                }
                if (stopCondition != null) {
                    sb.append("停止条件：").append(stopCondition).append("\n");
                }
            }
            sb.append("\n");
        } else {
            sb.append("用户请求：").append(originalQuery);
            if (taskGoal != null && !taskGoal.isBlank()) {
                sb.append("（").append(taskGoal).append("）");
            }
            sb.append("\n");
        }

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
     * 证据级别（信息深度维度）
     *
     * 区分工具结果的信息量，让 CompletionGate 根据任务需求做判断：
     * - "我有哪些笔记？" → LIST 就够
     * - "找一下线程池笔记" → SEARCH 就够
     * - "长江三峡那篇写了什么？" → 必须 CONTENT
     * - "生成思维导图" → ARTIFACT（生成类任务，不需要检索证据）
     */
    public enum EvidenceLevel {
        NONE,             // 无任何证据
        LIST_EVIDENCE,    // 仅有标题和摘要（listNotes）
        SEARCH_EVIDENCE,  // 有匹配内容和预览（searchNotes）
        CONTENT_EVIDENCE, // 有完整笔记正文（getNote）
        ARTIFACT_EVIDENCE // 已生成产物（generateMindMap, generateDiagram）
    }

    /**
     * 根据 Supervisor 指定的工具，推断完成目标所需的最低证据级别。
     *
     * 设计原则：不是"这是什么任务类型"，而是"这个工具会产生什么级别的证据"。
     * 避免任务类型爆炸（READ/WRITE/GENERATE/ANALYZE...），
     * 改为：工具 → 预期证据级别。
     *
     * @return 所需最低证据级别，null 表示不需要证据检查（写操作、统计等）
     */
    public EvidenceLevel expectedEvidenceLevel() {
        if (requiredTool == null || requiredTool.isBlank()) return null;

        return switch (requiredTool) {
            case "generateMindMap", "generateDiagram" -> EvidenceLevel.ARTIFACT_EVIDENCE;
            case "getNote" -> EvidenceLevel.CONTENT_EVIDENCE;
            case "searchNotes", "ragSummary", "getRelatedNotes" -> EvidenceLevel.SEARCH_EVIDENCE;
            case "listNotes", "getRecentNotes" -> EvidenceLevel.LIST_EVIDENCE;
            // 写操作、统计、复习：不需要检索证据，由 writeConfirmation 或工具成功直接判断
            case "createNote", "editNote", "appendNote", "deleteNote", "mergeNotes",
                 "getNoteStats", "getTodayReviews", "markReviewed", "scheduleReview" -> null;
            default -> null;
        };
    }
}
