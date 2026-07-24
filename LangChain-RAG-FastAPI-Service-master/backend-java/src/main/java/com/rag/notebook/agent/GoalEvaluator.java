package com.rag.notebook.agent;

import com.rag.notebook.agent.AgentState.EvidenceLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 目标评估器：在每次工具调用后主动判断"目标是否已达成"。
 *
 * 与旧 CompletionGate 的关键区别：
 *   CompletionGate 是"拦截器"——LLM 想停的时候才检查，导致 ping-pong。
 *   GoalEvaluator  是"主动评估"——每次工具调用后立即判断，达成直接结束。
 *
 * 核心逻辑：
 *   1. 工具执行成功 → 更新目标进度
 *   2. 检查所有 requiredArtifacts 是否已产出
 *   3. 全部产出 → GOAL_ACHIEVED，AgentLoop 直接结束
 *   4. 未全部产出 → 生成进度信息注入 Observation，让 LLM 知道"还缺什么"
 *
 * 特殊规则（兼容旧 CompletionGate 逻辑）：
 *   - 产物类工具（generateMindMap/generateDiagram）成功 = 目标达成
 *   - 写操作工具（createNote/editNote/appendNote）成功 = 目标达成
 *   - 检索类工具成功 = 证据已获取，需要 LLM 基于证据回答
 */
@Slf4j
@Component
public class GoalEvaluator {

    /** 产物类工具：调用成功即意味着任务完成 */
    private static final Set<String> ARTIFACT_TOOLS = Set.of(
            "generateMindMap", "generateDiagram"
    );

    /** 写操作工具：调用成功即意味着任务完成 */
    private static final Set<String> WRITE_TOOLS = Set.of(
            "createNote", "editNote", "appendNote", "deleteNote",
            "mergeNotes", "markReviewed", "scheduleReview"
    );

    /**
     * 在工具调用成功后立即评估目标是否已达成。
     *
     * @param state    当前 Agent 状态
     * @param toolName 刚执行成功的工具名
     * @return 评估结果
     */
    public GoalEvaluation evaluateAfterToolSuccess(AgentState state, String toolName) {
        // 产物类工具：调用成功 = 目标达成
        if (ARTIFACT_TOOLS.contains(toolName)) {
            state.markArtifactProduced(mapToolToArtifact(toolName));
            state.setTaskStatus(TaskStatus.GOAL_ACHIEVED);
            log.info("GoalEvaluator: 产物工具 {} 成功 → 目标达成", toolName);
            return GoalEvaluation.achieved(
                    "产物已生成: " + toolName,
                    state.getProducedArtifacts(),
                    state.getRequiredArtifacts()
            );
        }

        // 写操作工具：调用成功 = 目标达成
        if (WRITE_TOOLS.contains(toolName)) {
            state.markArtifactProduced(mapToolToArtifact(toolName));
            state.setTaskStatus(TaskStatus.GOAL_ACHIEVED);
            log.info("GoalEvaluator: 写操作 {} 成功 → 目标达成", toolName);
            return GoalEvaluation.achieved(
                    "写操作已完成: " + toolName,
                    state.getProducedArtifacts(),
                    state.getRequiredArtifacts()
            );
        }

        // 检索类工具：证据已获取，检查是否有明确的目标 artifacts 要求
        if (state.hasGoal() && !state.getRequiredArtifacts().isEmpty()) {
            // 检索工具成功也可能产出 artifact（如 getNote 产出 note_content）
            String artifact = mapToolToArtifact(toolName);
            if (artifact != null) {
                state.markArtifactProduced(artifact);
            }

            List<String> missing = computeMissing(state);
            if (missing.isEmpty()) {
                state.setTaskStatus(TaskStatus.GOAL_ACHIEVED);
                log.info("GoalEvaluator: 所有 artifacts 已产出 → 目标达成");
                return GoalEvaluation.achieved(
                        "所有目标产物已生成",
                        state.getProducedArtifacts(),
                        state.getRequiredArtifacts()
                );
            }

            // 有明确目标但还没完成
            log.info("GoalEvaluator: 进度 {}/{}, 还缺 {}",
                    state.getProducedArtifacts().size(),
                    state.getRequiredArtifacts().size(),
                    missing);
            return GoalEvaluation.inProgress(
                    state.getProducedArtifacts(),
                    state.getRequiredArtifacts(),
                    missing,
                    buildProgressMessage(state, missing)
            );
        }

        // 无明确目标或纯检索任务：继续让 LLM 决策
        return GoalEvaluation.undetermined();
    }

    /**
     * 在 LLM 返回文本（非工具调用）时评估。
     * 保留旧 CompletionGate 的拦截逻辑。
     */
    public GoalEvaluation evaluateOnTextResponse(AgentState state, String llmAnswer) {
        // 规则 0：写操作已确认 — 完成
        if (state.hasWriteConfirmation()) {
            state.setTaskStatus(TaskStatus.GOAL_ACHIEVED);
            return GoalEvaluation.achieved("写操作已确认", state.getProducedArtifacts(), state.getRequiredArtifacts());
        }

        // 规则 0b：已生成产物 — 完成
        if (state.hasEvidence(EvidenceLevel.ARTIFACT_EVIDENCE)) {
            state.setTaskStatus(TaskStatus.GOAL_ACHIEVED);
            return GoalEvaluation.achieved("产物已生成", state.getProducedArtifacts(), state.getRequiredArtifacts());
        }

        // 规则 1：要求调工具但一次都没成功
        if (state.isToolRequired() && !state.hasSuccessfulToolCall()) {
            return GoalEvaluation.blocked(
                    "任务要求调用工具，但尚未获得有效结果",
                    buildToolRequiredPrompt(state)
            );
        }

        // 规则 1b：工具执行了但全是 POOR
        if (state.isToolRequired() && !state.hasGoodQualityResult() && state.hasSuccessfulToolCall()) {
            return GoalEvaluation.blocked(
                    "工具已执行但结果质量不足",
                    buildPoorQualityPrompt(state)
            );
        }

        // 规则 2：证据级别不足
        EvidenceLevel expected = state.expectedEvidenceLevel();
        if (expected != null && !state.hasEvidence(expected)) {
            return GoalEvaluation.blocked(
                    "证据级别 " + state.getHighestEvidence() + " 未达 " + expected,
                    buildEvidenceGapPrompt(state, expected)
            );
        }

        // 有明确目标但还没达成
        if (state.hasGoal() && !state.getRequiredArtifacts().isEmpty()) {
            List<String> missing = computeMissing(state);
            if (!missing.isEmpty()) {
                return GoalEvaluation.blocked(
                        "目标未达成，还缺: " + String.join(", ", missing),
                        buildProgressMessage(state, missing) + "\n\n请继续调用工具完成任务，不要直接回答。"
                );
            }
        }

        return GoalEvaluation.allowed();
    }

    // ============================================================
    // 进度消息构建
    // ============================================================

    private String buildProgressMessage(AgentState state, List<String> missing) {
        StringBuilder sb = new StringBuilder();
        sb.append("[目标进度]\n");
        sb.append("目标：").append(state.getGoalDescription()).append("\n");

        if (!state.getProducedArtifacts().isEmpty()) {
            sb.append("已产出：").append(String.join(", ", state.getProducedArtifacts())).append("\n");
        } else {
            sb.append("已产出：（无）\n");
        }

        if (!missing.isEmpty()) {
            sb.append("还缺：").append(String.join(", ", missing)).append("\n");
        }

        sb.append("进度：").append(state.getProducedArtifacts().size())
                .append("/").append(state.getRequiredArtifacts().size()).append("\n");

        if (!missing.isEmpty()) {
            sb.append("\n请继续调用合适的工具来产出缺失的部分，任务尚未完成。");
        } else {
            sb.append("\n目标已完成，请基于已获取的信息直接回答用户。");
        }

        return sb.toString();
    }

    private String buildToolRequiredPrompt(AgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前任务尚未完成。你还没有获得完成该任务所需的工具结果。\n\n");
        sb.append("任务目标：").append(state.getGoalDescription() != null
                ? state.getGoalDescription() : state.getOriginalQuery()).append("\n");
        sb.append("必须使用的工具：").append(state.getRequiredTool()).append("\n\n");

        if (!state.getFailedActions().isEmpty()) {
            sb.append("已尝试的失败动作：\n");
            for (String fa : state.getFailedActions()) {
                sb.append("  - ").append(fa).append("\n");
            }
            sb.append("不要重复这些动作。\n\n");
        }

        if (!state.getWorkingMemory().isEmpty()) {
            sb.append("已知事实：\n");
            for (String fact : state.getWorkingMemory()) {
                sb.append("  - ").append(fact).append("\n");
            }
            sb.append("\n");
        }

        sb.append("请继续调用合适的工具获取数据，不要直接回答。");
        return sb.toString();
    }

    private String buildPoorQualityPrompt(AgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("工具已成功执行，但返回的结果质量不足以回答用户问题。\n\n");
        sb.append("需要改变检索策略：换更通用/不同的关键词，或改用其他工具。\n\n");

        if (!state.getWorkingMemory().isEmpty()) {
            sb.append("已知事实：\n");
            for (String fact : state.getWorkingMemory()) {
                sb.append("  - ").append(fact).append("\n");
            }
        }

        sb.append("\n请选择与之前不同的策略继续获取证据，不要直接回答。");
        return sb.toString();
    }

    private String buildEvidenceGapPrompt(AgentState state, EvidenceLevel required) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前获得的信息不足以回答用户问题。\n\n");
        sb.append("当前证据级别：").append(state.getHighestEvidence()).append("\n");
        sb.append("需要证据级别：").append(required).append("\n");

        if (required == EvidenceLevel.CONTENT_EVIDENCE) {
            sb.append("建议：需要调用 getNote 读取完整笔记内容。\n");
        } else if (required == EvidenceLevel.SEARCH_EVIDENCE) {
            sb.append("建议：调用 searchNotes 搜索或 listNotes 浏览笔记。\n");
        }

        sb.append("\n请继续获取所需信息，不要直接回答。");
        return sb.toString();
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private List<String> computeMissing(AgentState state) {
        List<String> missing = new ArrayList<>();
        for (String required : state.getRequiredArtifacts()) {
            if (!state.getProducedArtifacts().contains(required)) {
                missing.add(required);
            }
        }
        return missing;
    }

    /**
     * 将工具名映射为 artifact 名称
     */
    private String mapToolToArtifact(String toolName) {
        return switch (toolName) {
            case "generateMindMap" -> "mindmap";
            case "generateDiagram" -> "diagram";
            case "createNote" -> "note_created";
            case "editNote" -> "note_updated";
            case "appendNote" -> "note_updated";
            case "deleteNote" -> "note_deleted";
            case "mergeNotes" -> "notes_merged";
            case "markReviewed" -> "review_marked";
            case "scheduleReview" -> "review_scheduled";
            case "getNote" -> "note_content";
            case "searchNotes" -> "search_results";
            case "listNotes" -> "note_list";
            case "ragSummary" -> "kb_summary";
            case "getNoteStats" -> "note_stats";
            case "getTodayReviews" -> "today_reviews";
            case "getRecentNotes" -> "recent_notes";
            case "getRelatedNotes" -> "related_notes";
            default -> null;
        };
    }

    // ============================================================
    // 评估结果记录
    // ============================================================

    public enum GoalStatus {
        /** 目标已达成，可以结束 */
        ACHIEVED,
        /** 进行中，还需要继续 */
        IN_PROGRESS,
        /** 被阻止，需要 LLM 调整策略 */
        BLOCKED,
        /** 允许通过（LLM 文本回答可以接受） */
        ALLOWED,
        /** 无法判断（没有明确目标） */
        UNDETERMINED
    }

    public record GoalEvaluation(
            GoalStatus status,
            String message,
            List<String> produced,
            List<String> required,
            List<String> missing,
            String progressMessage
    ) {
        public boolean isAchieved() { return status == GoalStatus.ACHIEVED || status == GoalStatus.ALLOWED; }
        public boolean isBlocked() { return status == GoalStatus.BLOCKED; }
        public boolean shouldContinue() { return status == GoalStatus.IN_PROGRESS || status == GoalStatus.BLOCKED; }

        static GoalEvaluation achieved(String msg, List<String> produced, List<String> required) {
            return new GoalEvaluation(GoalStatus.ACHIEVED, msg,
                    produced != null ? produced : List.of(),
                    required != null ? required : List.of(),
                    List.of(), null);
        }

        static GoalEvaluation inProgress(List<String> produced, List<String> required,
                                          List<String> missing, String progressMessage) {
            return new GoalEvaluation(GoalStatus.IN_PROGRESS,
                    "进行中: " + produced.size() + "/" + required.size(),
                    produced != null ? produced : List.of(),
                    required != null ? required : List.of(),
                    missing != null ? missing : List.of(),
                    progressMessage);
        }

        static GoalEvaluation blocked(String reason, String prompt) {
            return new GoalEvaluation(GoalStatus.BLOCKED, reason,
                    List.of(), List.of(), List.of(), prompt);
        }

        static GoalEvaluation allowed() {
            return new GoalEvaluation(GoalStatus.ALLOWED, "允许通过",
                    List.of(), List.of(), List.of(), null);
        }

        static GoalEvaluation undetermined() {
            return new GoalEvaluation(GoalStatus.UNDETERMINED, "无明确目标",
                    List.of(), List.of(), List.of(), null);
        }
    }
}
