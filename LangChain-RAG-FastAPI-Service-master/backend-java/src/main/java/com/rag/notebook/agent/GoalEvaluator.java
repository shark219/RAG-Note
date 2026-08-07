package com.rag.notebook.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * 目标评估器——用 LLM 自检代替硬编码规则，真正做到目标导向。
 *
 * 设计演进：
 *   v1: 硬编码 TOOL_CRITERION_MAP 字符串匹配 → LLM 换个说法就断
 *   v2: 只检查"调没调工具" → 调了工具但答非所问也放行
 *   v3: 让 LLM 自己判断"目标达成了吗" → 真正目标导向
 */
@Slf4j
@Component
public class GoalEvaluator {

    private final ModelFactory modelFactory;

    public GoalEvaluator(ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

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
     * 只记录状态，不再用字符串匹配判断目标是否达成。
     *
     * @return 写操作/产物工具成功 → ACHIEVED；其他 → UNDETERMINED（让 LLM 自己决定下一步）
     */
    public GoalEvaluation evaluateAfterToolSuccess(AgentState state, String toolName, String rawResult) {

        // 1. 构建 Observation
        Observation obs = buildObservation(toolName, rawResult);
        state.addObservation(obs);

        // 2. 读取类目标：拿到完整笔记内容即完成，避免继续执行后续生成/写入任务。
        if ("getNote".equals(toolName) && isReadNoteGoal(state)) {
            state.setTaskStatus(TaskStatus.GOAL_ACHIEVED);
            log.info("GoalEvaluator: 已读取完整笔记 → 当前读取目标达成");
            return GoalEvaluation.achieved(state, "已读取完整笔记");
        }

        // 3. 产生可见产物（思维导图、图表 → 前端渲染）→ 产物生成即任务完成
        if (VISIBLE_ARTIFACT_TOOLS.contains(toolName)) {
            Artifact artifact = buildArtifact(toolName, rawResult);
            state.addArtifact(artifact);
            if (requiresWriteBackAfterArtifact(state)) {
                log.info("GoalEvaluator: 产物 {} 已生成，但目标还要求写回笔记 → 继续执行", toolName);
                return GoalEvaluation.inProgress(state, toolName + " 已生成，但还需要写回笔记",
                        "[目标未完成] 产物已经生成，但用户还要求把产物写回原笔记。\n"
                                + "请不要再次调用 " + toolName + "。请使用前面已定位到的原笔记 noteId，调用 appendNote 或 editNote，"
                                + "把上方生成的 Mermaid/产物内容写入原笔记。");
            }
            state.setTaskStatus(TaskStatus.GOAL_ACHIEVED);
            log.info("GoalEvaluator: 产物 {} 已生成 → 目标达成", toolName);
            return GoalEvaluation.achieved(state, toolName + " 已生成");
        }

        // 4. 写操作成功 → 任务完成
        if (WRITE_TOOLS.contains(toolName)) {
            state.markWriteConfirmation(toolName + " 执行成功");
            if ("createNote".equals(toolName)) {
                state.setTaskStatus(TaskStatus.EXECUTING);
                return GoalEvaluation.inProgress(state, "已创建笔记，继续后续步骤",
                        "[目标未完成] 笔记已创建，但这只是中间步骤。请继续完成后续生成导图、追加内容或其它剩余步骤，不要停止。");
            }
            if ("appendNote".equals(toolName) && requiresWriteBackAfterArtifact(state)) {
                state.setTaskStatus(TaskStatus.GOAL_ACHIEVED);
                log.info("GoalEvaluator: 写回笔记成功且目标要求写回 → 目标达成");
                return GoalEvaluation.achieved(state, toolName + " 执行成功");
            }
            state.setTaskStatus(TaskStatus.GOAL_ACHIEVED);
            log.info("GoalEvaluator: 写操作 {} 成功 → 目标达成", toolName);
            return GoalEvaluation.achieved(state, toolName + " 执行成功");
        }

        // 5. 其他工具 → 继续，让 LLM 自己判断
        return GoalEvaluation.undetermined(state);
    }

    // ============================================================
    // LLM 返回文本时评估
    // ============================================================

    /**
     * LLM 想直接回答（不调工具）时调用。
     * 只做最低限度的防偷懒检查，不检查"目标是否全部完成"。
     *
     * @return BLOCKED → 拦截（LLM 在偷懒），ALLOWED → 放行（LLM 有资格回答）
     */
    public GoalEvaluation evaluateOnTextResponse(AgentState state, String llmAnswer) {

        // 目标已达成 → 放行
        if (state.isGoalAchieved()) {
            return GoalEvaluation.allowed(state);
        }

        // 写操作已确认 → 放行
        if (state.hasWriteConfirmation()) {
            state.setTaskStatus(TaskStatus.GOAL_ACHIEVED);
            return GoalEvaluation.allowed(state);
        }

        // 一个工具都没调过 → 判断是否需要工具
        if (!state.hasSuccessfulToolCall()) {
            // 通用知识问题不需要强求调工具（"G1是什么"、"JVM原理"等）
            if (isGeneralKnowledgeQuery(state.getOriginalQuery())) {
                log.info("GoalEvaluator: 放行 — 通用知识问题，无需工具");
                return GoalEvaluation.allowed(state);
            }
            log.info("GoalEvaluator: 拦截 — 查询涉及工具操作但未调用工具");
            return GoalEvaluation.blocked(state, buildMustCallToolPrompt(state));
        }

        // 调用过工具但全部 POOR/ERROR → 拦截（需要换策略）
        if (!state.hasGoodQualityResult()) {
            log.info("GoalEvaluator: 拦截 — 工具调用结果全为 POOR/ERROR");
            return GoalEvaluation.blocked(state, buildPoorQualityPrompt(state));
        }

        // 调用过工具且有成功 → 用 LLM 判断目标是否真正达成
        if (state.hasGoal() && !state.getGoal().isBlank()) {
            if (isInformationalAnswerQuery(state.getOriginalQuery())) {
                log.info("GoalEvaluator: 放行 — 解释/总结类问题已有成功工具结果，避免过度追问细节");
                return GoalEvaluation.allowed(state);
            }

            // 已有成功工具结果但已连续被拦截过 → 放行，避免无限循环
            if (state.getConsecutiveNoProgress() >= 1) {
                log.info("GoalEvaluator: 放行 — 已有成功工具结果且曾被拦截，避免无限循环");
                return GoalEvaluation.allowed(state);
            }

            GoalCheckResult check = checkGoalWithLLM(state.getGoal(), state.getOriginalQuery(), llmAnswer);
            if (check.achieved) {
                log.info("GoalEvaluator: LLM 判定目标已达成 → 放行");
                return GoalEvaluation.allowed(state);
            } else {
                log.info("GoalEvaluator: LLM 判定目标未达成: {} → 拦截", check.reason);
                return GoalEvaluation.blocked(state, buildGoalNotMetPrompt(state, check.reason));
            }
        }

        // 无明确目标 → 放行（自由对话场景）
        log.info("GoalEvaluator: 放行 — 无明确目标，自由对话");
        return GoalEvaluation.allowed(state);
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
        // 不再用关键词猜测结果好坏——关键词匹配会把笔记正文里的"异常"、"错误"误判为工具失败
        return new Observation(toolName, "success", summary, data);
    }

    private Artifact buildArtifact(String toolName, String rawResult) {
        String label = extractLabel(toolName, rawResult);
        Map<String, Object> meta = new HashMap<>();
        meta.put("tool", toolName);
        if (rawResult != null && !rawResult.isBlank()) {
            int maxLen = 50000;
            meta.put("content", rawResult.length() > maxLen ? rawResult.substring(0, maxLen) + "\n...(truncated)" : rawResult);
        }
        String artifactType = switch (toolName) {
            case "generateMindMap" -> "mindmap";
            case "generateDiagram" -> "diagram";
            default -> toolName;
        };
        return new Artifact(artifactType, UUID.randomUUID().toString().substring(0, 8), label, meta);
    }

    private String extractLabel(String toolName, String rawResult) {
        if (rawResult == null) return toolName;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("标题[：:]\\s*(.+)")
                .matcher(rawResult);
        if (m.find()) return m.group(1).trim();
        if ("generateMindMap".equals(toolName)) return "思维导图";
        if ("generateDiagram".equals(toolName)) return "图表";
        return toolName;
    }

    private boolean requiresWriteBackAfterArtifact(AgentState state) {
        String text = ((state.getOriginalQuery() != null ? state.getOriginalQuery() : "") + "\n"
                + (state.getGoal() != null ? state.getGoal() : "")).toLowerCase(Locale.ROOT);
        boolean wantsWriteBack = containsAny(text, "写进", "写入", "写回", "追加", "保存到", "放进",
                "贴到", "加到", "加上", "加入", "附到", "插入", "更新到");
        boolean noteTarget = containsAny(text, "原来笔记", "原来的笔记", "原笔记", "当前笔记", "这篇笔记",
                "笔记里", "笔记中", "笔记里面", "原文", "原来内容");
        return wantsWriteBack && noteTarget;
    }

    private boolean isReadNoteGoal(AgentState state) {
        String text = ((state.getGoal() != null ? state.getGoal() : "") + "\n"
                + (state.getOriginalQuery() != null ? state.getOriginalQuery() : "")).toLowerCase(Locale.ROOT);
        boolean wantsRead = containsAny(text, "读取", "查看", "打开", "找到并读取", "完整内容", "原笔记");
        boolean wantsArtifactOrWrite = containsAny(text, "生成导图", "生成思维导图", "写回", "写入", "追加", "加上", "加入");
        return wantsRead && !wantsArtifactOrWrite;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    /**
     * 判断查询是否为通用知识问题（不需要调用户数据工具）。
     */
    private boolean isGeneralKnowledgeQuery(String query) {
        if (query == null || query.isBlank()) return false;
        String q = query.toLowerCase();
        String[] dataKeywords = {
                "笔记", "复习", "我的", "知识库", "创建", "编辑", "删除", "搜索", "查找",
                "列出", "统计", "思维导图", "导图", "今天", "最近", "安排", "追加", "合并",
                "note", "review", "create", "edit", "delete", "search", "list", "find"
        };
        for (String kw : dataKeywords) {
            if (q.contains(kw)) return false;
        }
        return true;
    }

    private boolean isInformationalAnswerQuery(String query) {
        if (query == null || query.isBlank()) return false;
        String q = query.toLowerCase(Locale.ROOT);
        boolean asksForExplanation = containsAny(q,
                "讲解", "解释", "介绍", "说明", "总结", "概述", "说一下", "聊一下", "是什么", "怎么理解",
                "explain", "summary", "summarize", "overview", "what is");
        boolean requiresOperation = containsAny(q,
                "创建", "新建", "编辑", "修改", "删除", "追加", "写入", "写回", "保存", "生成导图",
                "安排", "标记", "合并", "create", "edit", "delete", "append", "save");
        return asksForExplanation && !requiresOperation;
    }

    /**
     * 用 LLM 判断目标是否达成。这是真正智能的判断——LLM 理解目标的语义，
     * 不会因为关键词对不上就误判。
     */
    private GoalCheckResult checkGoalWithLLM(String goal, String originalQuery, String llmAnswer) {
        try {
            ChatLanguageModel llm = modelFactory.createChatModel(0.0, Duration.ofSeconds(10));
            String answerPreview = llmAnswer.length() > 500 ? llmAnswer.substring(0, 500) : llmAnswer;
            String prompt = String.format("""
                    用户提问：%s
                    任务目标：%s
                    AI 准备回答：%s

                    请判断：AI 的回答是否完成了上述目标？
                    只回答 YES 或 NO，如果 NO 请用一句话说明缺少什么。""",
                    originalQuery, goal, answerPreview);

            List<ChatMessage> messages = List.of(
                    SystemMessage.from("你是一个目标检查器。只回答 YES 或 NO+原因。"),
                    UserMessage.from(prompt)
            );
            Response<AiMessage> response = llm.generate(messages);
            String result = response.content().text().trim();
            boolean achieved = result.toUpperCase().startsWith("YES");
            String reason = result.length() > 3 ? result.substring(3).trim() : result;
            if (reason.startsWith(",") || reason.startsWith("，")) reason = reason.substring(1).trim();
            return new GoalCheckResult(achieved, reason);
        } catch (Exception e) {
            log.warn("LLM 目标检查失败，默认放行: {}", e.getMessage());
            return new GoalCheckResult(true, "检查异常，默认放行");
        }
    }

    private record GoalCheckResult(boolean achieved, String reason) {}

    // ============================================================
    // 提示构建
    // ============================================================

    private String buildMustCallToolPrompt(AgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("[环境状态]\n");
        if (state.hasGoal()) {
            sb.append("目标：").append(state.getGoal()).append("\n");
        }
        sb.append("你还没有调用任何工具。该目标需要真实执行工具操作后才能回答。\n");
        sb.append("如果用户要求创建、编辑、追加、删除、安排复习或生成产物，请先调用对应工具；不要只用文字声称已经完成。");
        return sb.toString();
    }

    private String buildPoorQualityPrompt(AgentState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("[环境状态]\n");
        sb.append("你调用过工具但结果都不理想。\n\n");
        sb.append("最近观察：\n");
        for (int i = Math.max(0, state.getObservations().size() - 3); i < state.getObservations().size(); i++) {
            Observation o = state.getObservations().get(i);
            sb.append("  - ").append(o.action()).append(" → ").append(o.status())
                    .append("（").append(o.summary()).append("）\n");
        }
        sb.append("\n请换一种策略（换关键词、换工具）重新尝试，不要直接回答。");
        return sb.toString();
    }

    private String buildGoalNotMetPrompt(AgentState state, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("[目标检查]\n");
        sb.append("目标：").append(state.getGoal()).append("\n");
        sb.append("判定：目标未达成\n");
        sb.append("原因：").append(reason).append("\n\n");
        sb.append("请基于以上反馈继续调用工具完成任务，不要直接回答。");
        return sb.toString();
    }

    // ============================================================
    // 内部类型
    // ============================================================

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

        static GoalEvaluation inProgress(AgentState state, String msg, String prompt) {
            return new GoalEvaluation(GoalStatus.IN_PROGRESS, msg, prompt, state);
        }

        static GoalEvaluation blocked(AgentState state, String prompt) {
            return new GoalEvaluation(GoalStatus.BLOCKED, "需要先调用工具获取数据", prompt, state);
        }

        static GoalEvaluation allowed(AgentState state) {
            return new GoalEvaluation(GoalStatus.ALLOWED, "允许回答", null, state);
        }

        static GoalEvaluation undetermined(AgentState state) {
            return new GoalEvaluation(GoalStatus.UNDETERMINED, "继续", null, state);
        }
    }
}
