package com.rag.notebook.agent;

import com.rag.notebook.agent.AgentState.ResultQuality;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 工具执行结果评估器：用规则快速判断结果质量，驱动反思循环
 *
 * 核心设计：不同错误类型给出不同的恢复建议，让 Agent 知道"为什么失败"和"下一步该换什么策略"。
 *
 * Action → Observation → Reflect → Replan → Action
 */
@Slf4j
@Service
public class ToolResultEvaluator {

    /**
     * 评估工具执行结果的质量（优先使用结构化 ToolResult，降级到字符串匹配）
     */
    public Evaluation evaluate(String toolName, String args, String result,
                                String originalQuery, ToolResult toolResult) {
        // 优先用结构化结果判断
        if (toolResult != null) {
            return switch (toolResult.status()) {
                case SUCCESS -> new Evaluation(ResultQuality.GOOD, null, null);
                case EMPTY -> {
                    String suggestion = suggestFix(toolName, args, "no_result");
                    yield new Evaluation(ResultQuality.POOR, "搜索无结果", suggestion);
                }
                case ERROR -> {
                    String errorCode = toolResult.errorCode() != null ? toolResult.errorCode() : "UNKNOWN_ERROR";
                    String reason = buildErrorReason(errorCode);
                    String suggestion = suggestFixByErrorCode(toolName, args, errorCode);
                    yield new Evaluation(ResultQuality.ERROR, reason, suggestion);
                }
            };
        }

        // 降级：字符串匹配
        if (result == null || result.isBlank()) {
            return new Evaluation(ResultQuality.ERROR, "工具返回为空", suggestFix(toolName, args, "empty"));
        }
        if (result.contains("未找到") || result.contains("没有找到") || result.contains("无相关")) {
            return new Evaluation(ResultQuality.POOR, "搜索无结果", suggestFix(toolName, args, "no_result"));
        }
        if (result.contains("失败") || result.contains("错误") || result.contains("异常")) {
            return new Evaluation(ResultQuality.ERROR, "工具执行出错", "请检查参数后重试");
        }
        return new Evaluation(ResultQuality.GOOD, null, null);
    }

    /**
     * 将错误码转成人类可读的失败原因
     */
    private String buildErrorReason(String errorCode) {
        return switch (errorCode) {
            case "NOTE_NOT_FOUND" -> "提供的 noteId 无效，该笔记不存在";
            case "RAG_ERROR" -> "知识库检索失败";
            case "SEARCH_ERROR" -> "笔记搜索执行失败";
            case "LIST_ERROR" -> "获取笔记列表失败";
            case "EXCEPTION" -> "工具执行时发生异常";
            default -> "工具执行出错: " + errorCode;
        };
    }

    /**
     * 根据错误码给出恢复策略（核心：告诉 Agent "下一步该换什么"）
     */
    public String suggestFixByErrorCode(String toolName, String args, String errorCode) {
        return switch (errorCode) {
            case "NOTE_NOT_FOUND" -> {
                if ("getNote".equals(toolName)) {
                    yield "提供的参数可能不是有效的 noteId（标题/关键词≠noteId）。"
                            + "请先调用 searchNotes 或 listNotes 定位笔记，获取正确的 noteId 后再调用 getNote。";
                }
                if ("editNote".equals(toolName) || "appendNote".equals(toolName) || "deleteNote".equals(toolName)) {
                    yield "该 noteId 不存在，请先调用 searchNotes 或 listNotes 确认笔记 ID。";
                }
                yield "请先通过 searchNotes 或 listNotes 确认资源是否存在。";
            }
            case "RAG_ERROR" -> "知识库检索暂时不可用，可以尝试用 searchNotes 在笔记中搜索相关内容。";
            case "SEARCH_ERROR" -> "搜索执行失败，请尝试用更简单的关键词或改用 listNotes 浏览笔记。";
            case "EXCEPTION" -> "该操作暂时不可用，请尝试其他工具完成相同目标。";
            default -> "请调整参数后重试，或使用其他工具完成相同目标。";
        };
    }

    /**
     * 生成反思提示消息，注入到 Agent 循环中引导 LLM 换策略
     */
    public String buildReflectionMessage(Evaluation eval, String toolName, String args) {
        StringBuilder sb = new StringBuilder();
        sb.append("[反思] 工具 ").append(toolName).append(" 的结果不够理想：").append(eval.reason());
        if (eval.suggestion() != null) {
            sb.append("\n建议：").append(eval.suggestion());
        }
        return sb.toString();
    }

    /**
     * 生成结构化反思，用于注入到 Agent 的工作记忆中
     */
    public ReflectionResult buildStructuredReflection(
            String toolName, String args, Evaluation eval, AgentState state) {

        String failureAnalysis = eval.reason();
        String strategyChange = eval.suggestion();
        String nextAction = inferNextAction(toolName, eval);

        // 构建要避免的动作描述
        String avoidDesc = toolName + "(" + args + ")";

        // 自动将反思结果写入工作记忆
        if (failureAnalysis != null) {
            state.addKnownFact(failureAnalysis);
        }
        state.addFailedAction(avoidDesc);

        return new ReflectionResult(failureAnalysis, strategyChange, nextAction, avoidDesc);
    }

    /**
     * 根据当前工具和评估结果推断下一步应该调用什么工具
     */
    private String inferNextAction(String toolName, Evaluation eval) {
        if (eval.quality() == ResultQuality.GOOD) return null;

        // getNote 失败 → 先搜索
        if ("getNote".equals(toolName)) {
            return "searchNotes 或 listNotes";
        }
        // 搜索无结果 → 换工具
        if (isSearchTool(toolName) && eval.quality() == ResultQuality.POOR) {
            return "listNotes（浏览全部笔记）";
        }
        // 列表为空 → 可能没有笔记
        if ("listNotes".equals(toolName) && eval.quality() == ResultQuality.POOR) {
            return null; // 没有笔记是合理的
        }
        return null;
    }

    private boolean isSearchTool(String toolName) {
        return "searchNotes".equals(toolName) || "ragSummary".equals(toolName)
                || "getRelatedNotes".equals(toolName);
    }

    private String suggestFix(String toolName, String args, String problemType) {
        return switch (problemType) {
            case "empty" -> "尝试用更简短的关键词重试";
            case "no_result" -> {
                if ("searchNotes".equals(toolName)) {
                    yield "换个更通用或更简短的关键词重试，或者用 listNotes 浏览全部笔记";
                }
                if ("ragSummary".equals(toolName)) {
                    yield "换个更宽泛的关键词重试，或者用 searchNotes 在笔记中搜索";
                }
                yield "换个关键词重试，比如去掉修饰词、用更通用的表述";
            }
            case "too_few" -> "尝试用更宽泛的关键词搜索";
            default -> "请检查参数后重试";
        };
    }

    /**
     * 评估结果
     */
    public record Evaluation(ResultQuality quality, String reason, String suggestion) {}

    /**
     * 结构化反思结果
     */
    public record ReflectionResult(
            String failureAnalysis,
            String strategyChange,
            String nextAction,
            String avoid
    ) {}
}
