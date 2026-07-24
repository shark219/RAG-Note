package com.rag.notebook.agent;

import com.rag.notebook.agent.AgentState.EvidenceLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 完成门控：工具箱驱动的目标满足判断。
 *
 * 核心原则：不是"这是什么任务类型"，而是"这个工具会产生什么级别的证据，
 * 当前状态是否达到了那个级别"。
 *
 * 不需要 TaskIntent 枚举（避免 READ/WRITE/GENERATE/ANALYZE 无限增长），
 * 改为：requiredTool → expectedEvidenceLevel() → 比较当前证据级别。
 */
@Slf4j
@Component
public class CompletionGate {

    public GateResult check(AgentState state, String currentAnswer) {
        // 规则 0：写操作已确认 — 直接完成
        if (state.hasWriteConfirmation()) {
            log.info("CompletionGate 放行: 写操作已确认 ({})", state.getWriteConfirmation());
            return GateResult.ALLOWED;
        }

        // 规则 0b：已生成产物 — 直接完成
        if (state.hasEvidence(EvidenceLevel.ARTIFACT_EVIDENCE)) {
            log.info("CompletionGate 放行: 已生成产物");
            return GateResult.ALLOWED;
        }

        // 规则 1：要求调工具但一次都没执行成功过
        if (state.isToolRequired() && !state.hasSuccessfulToolCall()) {
            String reason = state.getToolHistory().isEmpty()
                    ? "任务要求调用工具，但尚未调用任何工具"
                    : "所有工具调用都执行失败（ERROR），尚未获得任何有效结果";
            log.info("CompletionGate 拦截: {}", reason);
            return new GateResult(false, reason, buildToolRequiredRetryPrompt(state));
        }

        // 规则 1b：工具执行了但全是 POOR，证据质量不足
        if (state.isToolRequired() && !state.hasGoodQualityResult()
                && state.hasSuccessfulToolCall()) {
            String reason = "工具已执行但返回结果质量不足（均为 POOR），需要改善检索策略";
            log.info("CompletionGate 拦截: {}", reason);
            return new GateResult(false, reason, buildPoorQualityRetryPrompt(state));
        }

        // 规则 2：证据级别是否达到工具预期
        EvidenceLevel expected = state.expectedEvidenceLevel();
        if (expected != null && !state.hasEvidence(expected)) {
            String reason = "当前证据级别 " + state.getHighestEvidence()
                    + " 未达到工具 " + state.getRequiredTool() + " 所需的 " + expected;
            log.info("CompletionGate 拦截: {}", reason);
            return new GateResult(false, reason, buildEvidenceGapRetryPrompt(state, expected));
        }

        // 规则 3：有成功的工具调用 — 放行
        if (state.isToolRequired() && state.hasSuccessfulToolCall()) {
            return GateResult.ALLOWED;
        }

        return GateResult.ALLOWED;
    }

    // ============================================================
    // 重试提示构建
    // ============================================================

    private String buildToolRequiredRetryPrompt(AgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前任务尚未完成。你还没有获得完成该任务所需的工具结果。\n\n");
        sb.append("任务目标：").append(state.getTaskGoal() != null ? state.getTaskGoal() : state.getOriginalQuery()).append("\n");
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

    private String buildPoorQualityRetryPrompt(AgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("工具已成功执行，但返回的结果质量不足以回答用户问题。\n\n");

        sb.append("已尝试的工具调用：\n");
        for (AgentState.ToolCallRecord r : state.getToolHistory()) {
            sb.append("  - ").append(r.toolName()).append(" → ").append(r.quality()).append("\n");
        }
        sb.append("\n");

        sb.append("需要改变检索策略：\n");
        sb.append("1. searchNotes POOR → 换更通用/不同的关键词，或改用 listNotes\n");
        sb.append("2. listNotes POOR → 试试 searchNotes 用具体关键词\n");
        sb.append("3. ragSummary POOR → 换更宽泛的查询，或改用 searchNotes\n\n");

        if (!state.getWorkingMemory().isEmpty()) {
            sb.append("已知事实：\n");
            for (String fact : state.getWorkingMemory()) {
                sb.append("  - ").append(fact).append("\n");
            }
            sb.append("\n");
        }

        sb.append("请选择与之前不同的策略继续获取证据，不要直接回答。");
        return sb.toString();
    }

    private String buildEvidenceGapRetryPrompt(AgentState state, EvidenceLevel required) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前获得的信息不足以回答用户问题。\n\n");
        sb.append("当前证据级别：").append(state.getHighestEvidence()).append("\n");
        sb.append("需要证据级别：").append(required).append("\n\n");

        if (required == EvidenceLevel.CONTENT_EVIDENCE) {
            if (state.getHighestEvidence().ordinal() < EvidenceLevel.SEARCH_EVIDENCE.ordinal()) {
                sb.append("建议：先调用 searchNotes 搜索相关笔记获取 noteId，")
                        .append("然后调用 getNote(noteId) 读取完整内容。\n");
            } else {
                sb.append("建议：你已经找到了相关笔记，现在需要用 getNote 读取完整内容。\n");
                for (String fact : state.getWorkingMemory()) {
                    if (fact.contains("ID:")) {
                        sb.append("可用的笔记 ID 线索：").append(fact).append("\n");
                        break;
                    }
                }
            }
        } else if (required == EvidenceLevel.SEARCH_EVIDENCE) {
            sb.append("建议：调用 searchNotes 搜索相关笔记，或调用 listNotes 浏览全部笔记。\n");
        }

        if (!state.getFailedActions().isEmpty()) {
            sb.append("\n已失败的动作（不要重复）：\n");
            for (String fa : state.getFailedActions()) {
                sb.append("  - ").append(fa).append("\n");
            }
        }

        if (!state.getWorkingMemory().isEmpty()) {
            sb.append("\n已知事实：\n");
            for (String fact : state.getWorkingMemory()) {
                sb.append("  - ").append(fact).append("\n");
            }
        }

        sb.append("\n请继续获取所需信息，不要直接回答。");
        return sb.toString();
    }

    public record GateResult(boolean allowed, String reason, String retryPrompt) {
        public static final GateResult ALLOWED = new GateResult(true, null, null);
    }
}
