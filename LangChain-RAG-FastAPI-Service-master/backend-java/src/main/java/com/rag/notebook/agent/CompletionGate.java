package com.rag.notebook.agent;

import com.rag.notebook.agent.AgentState.EvidenceLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 完成门控：判断 Agent 是否满足结束条件。
 *
 * 核心原则：LLM 不调用工具 ≠ 任务完成。
 * 只有当用户目标被满足时，才允许结束。
 *
 * 证据级别感知：
 *   - "我有哪些笔记？" → LIST_EVIDENCE 足够
 *   - "找一下线程池笔记" → SEARCH_EVIDENCE 足够
 *   - "长江三峡那篇写了什么？" → 必须 CONTENT_EVIDENCE
 */
@Slf4j
@Component
public class CompletionGate {

    /**
     * 判断 Agent 是否可以结束
     */
    public GateResult check(AgentState state, String currentAnswer) {
        // 规则 0：写操作已确认成功 -- 任务核心动作完成，直接放行，无需证据检查
        if (state.hasWriteConfirmation()) {
            log.info("CompletionGate 放行: 写操作已确认 ({})", state.getWriteConfirmation());
            return GateResult.ALLOWED;
        }

        // 规则 1：Supervisor 要求调用工具，但工具完全没有执行成功过
        // 注意：POOR 也算执行成功（工具跑了，只是结果质量不够）
        // hasSuccessfulToolCall() 现在检查的是 != ERROR（不是 != GOOD）
        if (state.isToolRequired() && !state.hasSuccessfulToolCall()) {
            String reason;
            if (state.getToolHistory().isEmpty()) {
                reason = "任务要求调用工具，但尚未调用任何工具";
            } else {
                reason = "所有工具调用都执行失败（ERROR），尚未获得任何有效结果";
            }
            log.info("CompletionGate 拦截: {}", reason);
            String retryPrompt = buildToolRequiredRetryPrompt(state);
            return new GateResult(false, reason, retryPrompt);
        }

        // 规则 1b：工具有执行但全是 POOR，证据质量不足以回答
        if (state.isToolRequired() && !state.hasGoodQualityResult()
                && state.hasSuccessfulToolCall()) {
            String reason = "工具已执行但返回结果质量不足（均为 POOR），需要改善检索策略";
            log.info("CompletionGate 拦截: {}", reason);
            String retryPrompt = buildPoorQualityRetryPrompt(state);
            return new GateResult(false, reason, retryPrompt);
        }

        // 规则 2：任务目标涉及笔记，但证据级别不足
        // 仅对 READ/SEARCH/LIST 意图检查证据，写操作已在 Rule 0 处理
        AgentState.TaskIntent intent = state.inferIntent();
        if (intent != AgentState.TaskIntent.WRITE_NOTE
                && intent != AgentState.TaskIntent.REVIEW
                && intent != AgentState.TaskIntent.GENERATE_ARTIFACT
                && (involvesNote(state.getTaskGoal()) || involvesNote(state.getOriginalQuery()))) {
            EvidenceLevel required = inferRequiredEvidence(state);
            if (!state.hasEvidence(required)) {
                String reason = "任务需要 " + required + " 级别的证据，但当前只有 " + state.getHighestEvidence();
                log.info("CompletionGate 拦截: {}", reason);
                String retryPrompt = buildEvidenceGapRetryPrompt(state, required);
                return new GateResult(false, reason, retryPrompt);
            }
        }

        // 规则 3：有成功的工具调用且证据足够，允许结束
        if (state.isToolRequired() && state.hasSuccessfulToolCall()) {
            return GateResult.ALLOWED;
        }

        return GateResult.ALLOWED;
    }

    /**
     * 从任务目标中推断所需的最低证据级别
     */
    EvidenceLevel inferRequiredEvidence(AgentState state) {
        String combined = (state.getTaskGoal() != null ? state.getTaskGoal() + " " : "")
                + state.getOriginalQuery();

        // 需要完整内容：看了什么、具体内容、写了什么
        if (matches(combined,
                "写了什么", "具体内容", "看了什么", "内容是什么", "读一下",
                "看看.*那篇", "打开.*笔记", "这篇笔记", "读了什么",
                "总结一下", "帮我总结", "概括", "梳理")) {
            return EvidenceLevel.CONTENT_EVIDENCE;
        }

        // 需要搜索匹配：找一下、有没有、搜索
        if (matches(combined,
                "找一下", "有没有", "搜索", "查找", "找.*笔记",
                "关于.*笔记", "相关笔记")) {
            return EvidenceLevel.SEARCH_EVIDENCE;
        }

        // 默认：列表级别就够了（有哪些、列表、总览、最近的笔记）
        return EvidenceLevel.LIST_EVIDENCE;
    }

    private boolean matches(String text, String... patterns) {
        for (String pattern : patterns) {
            if (java.util.regex.Pattern.compile(pattern).matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构建"必须调用工具"的重试提示
     */
    private String buildToolRequiredRetryPrompt(AgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前任务尚未完成。你还没有获得完成该任务所需的工具结果。\n\n");
        sb.append("任务目标：").append(state.getTaskGoal() != null ? state.getTaskGoal() : state.getOriginalQuery()).append("\n");
        sb.append("必须使用的工具：").append(state.getRequiredTool()).append("\n\n");

        if (!state.getFailedActions().isEmpty()) {
            sb.append("你已经尝试过以下失败的动作：\n");
            for (String fa : state.getFailedActions()) {
                sb.append("  - ").append(fa).append("\n");
            }
            sb.append("请不要重复这些动作，尝试不同的工具或参数。\n\n");
        }

        if (!state.getWorkingMemory().isEmpty()) {
            sb.append("当前已知事实：\n");
            for (String fact : state.getWorkingMemory()) {
                sb.append("  - ").append(fact).append("\n");
            }
            sb.append("\n");
        }

        sb.append("请继续调用合适的工具获取数据，不要直接回答。");
        return sb.toString();
    }

    /**
     * 构建"所有结果质量均为 POOR"的重试提示
     */
    private String buildPoorQualityRetryPrompt(AgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("工具已成功执行，但返回的结果质量不足以回答用户问题。\n\n");

        sb.append("已尝试的工具调用：\n");
        for (AgentState.ToolCallRecord r : state.getToolHistory()) {
            sb.append("  - ").append(r.toolName()).append(" → ").append(r.quality()).append("\n");
        }
        sb.append("\n");

        sb.append("你需要改变检索策略，而不是重复刚才的动作：\n");
        sb.append("1. 如果是 searchNotes POOR → 换更通用/不同的关键词，或改用 listNotes\n");
        sb.append("2. 如果是 listNotes POOR → 试试 searchNotes 用具体关键词\n");
        sb.append("3. 如果是 ragSummary POOR → 换更宽泛的查询，或改用 searchNotes\n\n");

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

    /**
     * 构建"证据级别不足"的重试提示
     */
    private String buildEvidenceGapRetryPrompt(AgentState state, EvidenceLevel required) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前获得的信息不足以回答用户问题。\n\n");

        sb.append("当前证据级别：").append(state.getHighestEvidence()).append("\n");
        sb.append("需要证据级别：").append(required).append("\n\n");

        // 根据差距给出具体建议
        if (required == EvidenceLevel.CONTENT_EVIDENCE) {
            if (state.getHighestEvidence().ordinal() < EvidenceLevel.SEARCH_EVIDENCE.ordinal()) {
                sb.append("建议：先调用 searchNotes 搜索相关笔记获取 noteId，")
                        .append("然后调用 getNote(noteId) 读取完整内容。\n");
            } else {
                // 已经有 SEARCH 或 LIST 证据，直接建议 getNote
                sb.append("建议：你已经找到了相关笔记，现在需要用 getNote 读取完整内容。\n");
                // 尝试从工作记忆中提取可用的 noteId
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

    private boolean involvesNote(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase();
        return lower.contains("笔记") || lower.contains("note")
                || lower.contains("记录") || lower.contains("写了什么");
    }

    public record GateResult(boolean allowed, String reason, String retryPrompt) {
        public static final GateResult ALLOWED = new GateResult(true, null, null);
    }
}
