package com.rag.notebook.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 执行状态——以目标为中心，不依赖工具。
 *
 * 核心设计转变：
 *   旧：requiredTool → 必须调哪个工具（工具中心）
 *   新：goal → successCriteria → 达成即止（目标中心）
 *
 * 状态结构：
 *   goal             — 一句话目标描述
 *   successCriteria  — 判断"完成"的条件列表
 *   resources        — Agent 可操作的资源（笔记、文档等）
 *   observations     — 每次工具调用的结构化观察
 *   artifacts        — 已产出的产物（思维导图、图表、新建笔记等）
 *   pendingSteps     — 目标未达成时，还缺什么
 *   completedSteps   — 已完成的步骤
 *
 * 工作记忆和反思：
 *   workingMemory    — "学到了什么"（不断增长）
 *   failedActions    — "什么路径不通"（避免重复）
 *   toolHistory      — 完整调用审计
 *
 * 证据级别（检索深度，区分信息量）：
 *   LIST_EVIDENCE    — 仅有标题/摘要
 *   SEARCH_EVIDENCE  — 有内容预览
 *   CONTENT_EVIDENCE — 有完整正文
 *   ARTIFACT_EVIDENCE — 有生成的产物
 */
public class AgentState {

    private final String originalQuery;

    // ========== 目标（核心） ==========
    private String goal;
    private final List<String> successCriteria = new ArrayList<>();
    private final List<String> pendingSteps = new ArrayList<>();
    private final List<String> completedSteps = new ArrayList<>();

    // ========== 环境状态 ==========
    private final List<Resource> resources = new ArrayList<>();
    private final List<Observation> observations = new ArrayList<>();
    private final List<Artifact> artifacts = new ArrayList<>();

    // ========== 任务状态机 ==========
    private TaskStatus taskStatus = TaskStatus.CREATED;

    // ========== 工具调用审计 ==========
    private final List<ToolCallRecord> toolHistory = new ArrayList<>();

    // ========== 工作记忆 ==========
    private final List<String> workingMemory = new ArrayList<>();
    private final List<String> failedActions = new ArrayList<>();
    private int consecutiveNoProgress = 0;

    // ========== 证据级别 ==========
    private EvidenceLevel highestEvidence = EvidenceLevel.NONE;

    // ========== 操作确认 ==========
    private String writeConfirmation;
    private String bestAnswer;

    public AgentState(String originalQuery) {
        this.originalQuery = originalQuery;
    }

    // ============================================================
    // 目标
    // ============================================================

    public void setGoal(String goal) {
        this.goal = goal;
        if (goal != null && !goal.isBlank()) {
            this.taskStatus = TaskStatus.EXECUTING;
        }
    }

    public String getGoal() { return goal; }

    public boolean hasGoal() {
        return goal != null && !goal.isBlank();
    }

    public void setSuccessCriteria(List<String> criteria) {
        this.successCriteria.clear();
        if (criteria != null) this.successCriteria.addAll(criteria);
    }

    public List<String> getSuccessCriteria() { return new ArrayList<>(successCriteria); }

    public void addPendingStep(String step) {
        if (step != null && !step.isBlank()) pendingSteps.add(step);
    }

    public List<String> getPendingSteps() { return new ArrayList<>(pendingSteps); }

    public void markStepCompleted(String step) {
        if (step != null && !step.isBlank()) {
            pendingSteps.remove(step);
            if (!completedSteps.contains(step)) completedSteps.add(step);
        }
    }

    public List<String> getCompletedSteps() { return new ArrayList<>(completedSteps); }

    // ============================================================
    // 环境状态：资源、观察、产物
    // ============================================================

    public void addResource(Resource resource) {
        if (resource != null) resources.add(resource);
    }

    public List<Resource> getResources() { return new ArrayList<>(resources); }

    public void addObservation(Observation observation) {
        if (observation != null) observations.add(observation);
    }

    public List<Observation> getObservations() { return new ArrayList<>(observations); }

    public Observation lastObservation() {
        return observations.isEmpty() ? null : observations.get(observations.size() - 1);
    }

    /**
     * 添加产物并自动更新 completedSteps 和 workingMemory。
     * 产物是 GoalEvaluator 判断目标达成的核心依据。
     */
    public void addArtifact(Artifact artifact) {
        if (artifact == null) return;
        artifacts.add(artifact);
        addKnownFact("已产出: " + artifact.type() + (artifact.label() != null ? " — " + artifact.label() : ""));
        markStepCompleted(artifact.type());
        upgradeEvidence(EvidenceLevel.ARTIFACT_EVIDENCE);
    }

    public List<Artifact> getArtifacts() { return new ArrayList<>(artifacts); }

    public boolean hasArtifactOfType(String type) {
        return artifacts.stream().anyMatch(a -> a.type().equals(type));
    }

    // ============================================================
    // 任务状态机
    // ============================================================

    public TaskStatus getTaskStatus() { return taskStatus; }

    public void setTaskStatus(TaskStatus status) { this.taskStatus = status; }

    public boolean isGoalAchieved() { return taskStatus == TaskStatus.GOAL_ACHIEVED; }

    // ============================================================
    // 工具调用审计
    // ============================================================

    public String getOriginalQuery() { return originalQuery; }

    public List<ToolCallRecord> getToolHistory() { return toolHistory; }

    public void recordToolCall(String toolName, String args, String result, ResultQuality quality) {
        toolHistory.add(new ToolCallRecord(toolName, args, result, quality));
    }

    public String getBestAnswer() { return bestAnswer; }

    public void setBestAnswer(String bestAnswer) { this.bestAnswer = bestAnswer; }

    // ============================================================
    // 工作记忆
    // ============================================================

    public void addKnownFact(String fact) {
        if (fact != null && !fact.isBlank() && !workingMemory.contains(fact)) {
            workingMemory.add(fact);
        }
    }

    public List<String> getWorkingMemory() { return workingMemory; }

    public void addFailedAction(String description) {
        if (description != null && !description.isBlank()) {
            failedActions.add(description);
        }
    }

    public List<String> getFailedActions() { return failedActions; }

    // ============================================================
    // 进展追踪
    // ============================================================

    public void markProgress() { consecutiveNoProgress = 0; }

    public void markNoProgress() { consecutiveNoProgress++; }

    public int getConsecutiveNoProgress() { return consecutiveNoProgress; }

    public boolean needsReflection() { return consecutiveNoProgress >= 2; }

    // ============================================================
    // 查询方法
    // ============================================================

    public boolean hasCalledTool(String toolName) {
        return toolHistory.stream().anyMatch(r -> r.toolName().equals(toolName));
    }

    public boolean hasSuccessfulToolCall() {
        return toolHistory.stream().anyMatch(r -> r.quality() != ResultQuality.ERROR);
    }

    public boolean hasGoodQualityResult() {
        return toolHistory.stream().anyMatch(r -> r.quality() == ResultQuality.GOOD);
    }

    public boolean lastCallWasPoor() {
        if (toolHistory.isEmpty()) return false;
        return toolHistory.get(toolHistory.size() - 1).quality() == ResultQuality.POOR;
    }

    public boolean hasCalledWithArgsAndFailed(String toolName, String args) {
        return toolHistory.stream()
                .anyMatch(r -> r.toolName().equals(toolName)
                        && r.args() != null && r.args().equals(args)
                        && r.quality() == ResultQuality.ERROR);
    }

    public boolean hasSameAction(String toolName, String args) {
        if (toolHistory.isEmpty()) return false;
        ToolCallRecord last = toolHistory.get(toolHistory.size() - 1);
        return last.toolName().equals(toolName)
                && last.args() != null && last.args().equals(args);
    }

    public String getLastResult(String toolName) {
        for (int i = toolHistory.size() - 1; i >= 0; i--) {
            if (toolHistory.get(i).toolName().equals(toolName)) {
                return toolHistory.get(i).result();
            }
        }
        return null;
    }

    public boolean lastCallWasGood() {
        if (toolHistory.isEmpty()) return false;
        return toolHistory.get(toolHistory.size() - 1).quality() == ResultQuality.GOOD;
    }

    // ============================================================
    // 证据级别
    // ============================================================

    public void upgradeEvidence(EvidenceLevel level) {
        if (level.ordinal() > highestEvidence.ordinal()) {
            highestEvidence = level;
        }
    }

    public boolean hasEvidence(EvidenceLevel required) {
        return highestEvidence.ordinal() >= required.ordinal();
    }

    public EvidenceLevel getHighestEvidence() { return highestEvidence; }

    // ============================================================
    // 写操作确认
    // ============================================================

    public String getWriteConfirmation() { return writeConfirmation; }

    public void markWriteConfirmation(String message) {
        this.writeConfirmation = message;
        addKnownFact(message);
    }

    public boolean hasWriteConfirmation() { return writeConfirmation != null; }

    // ============================================================
    // 状态摘要
    // ============================================================

    /**
     * 构建结构化状态摘要——面向 LLM 的"环境状态视图"。
     *
     * 这是 Agent 反思和决策的信息基础：
     *   不是"你失败了请反思"，
     *   而是"这是当前环境状态，请基于状态决定下一步"。
     */
    public String buildStateSummary() {
        StringBuilder sb = new StringBuilder();

        // 目标
        sb.append("目标：").append(hasGoal() ? goal : originalQuery).append("\n");

        // 成功标准 + 进度
        if (!successCriteria.isEmpty()) {
            long met = successCriteria.stream()
                    .filter(sc -> completedSteps.contains(sc) || hasArtifactOfType(sc))
                    .count();
            sb.append("成功标准：").append(met).append("/").append(successCriteria.size())
                    .append(" 已满足\n");
            for (String sc : successCriteria) {
                boolean done = completedSteps.contains(sc) || hasArtifactOfType(sc);
                sb.append("  ").append(done ? "[✓]" : "[ ]").append(" ").append(sc).append("\n");
            }
        }

        // 资源
        if (!resources.isEmpty()) {
            sb.append("\n可用资源：\n");
            for (Resource r : resources) {
                sb.append("  - ").append(r.type()).append(": ").append(r.label());
                if (r.id() != null) sb.append(" (").append(r.id()).append(")");
                sb.append("\n");
            }
        }

        // 产物
        if (!artifacts.isEmpty()) {
            sb.append("\n已产出：\n");
            for (Artifact a : artifacts) {
                sb.append("  - ").append(a.type());
                if (a.label() != null) sb.append(": ").append(a.label());
                sb.append("\n");
            }
        }

        // 最近观察
        if (!observations.isEmpty()) {
            sb.append("\n最近观察：\n");
            int start = Math.max(0, observations.size() - 3);
            for (int i = start; i < observations.size(); i++) {
                Observation o = observations.get(i);
                sb.append("  ").append(i + 1).append(". ")
                        .append(o.action()).append(" → ").append(o.status());
                if (o.summary() != null) sb.append("（").append(o.summary()).append("）");
                sb.append("\n");
            }
        }

        // 工作记忆
        if (!workingMemory.isEmpty()) {
            sb.append("\n已知事实：\n");
            for (String fact : workingMemory) {
                sb.append("  - ").append(fact).append("\n");
            }
        }

        // 失败动作
        if (!failedActions.isEmpty()) {
            sb.append("失败动作（不要重复）：\n");
            for (String action : failedActions) {
                sb.append("  - ").append(action).append("\n");
            }
        }

        return sb.toString();
    }

    // ============================================================
    // 内部类型
    // ============================================================

    public record ToolCallRecord(String toolName, String args, String result, ResultQuality quality) {}

    public enum ResultQuality {
        GOOD, POOR, ERROR
    }

    public enum EvidenceLevel {
        NONE,
        LIST_EVIDENCE,
        SEARCH_EVIDENCE,
        CONTENT_EVIDENCE,
        ARTIFACT_EVIDENCE
    }
}
