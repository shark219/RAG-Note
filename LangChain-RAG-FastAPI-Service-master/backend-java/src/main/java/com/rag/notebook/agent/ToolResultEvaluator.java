package com.rag.notebook.agent;

import com.rag.notebook.agent.AgentState.ResultQuality;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 工具执行结果评估器：用规则快速判断结果质量，驱动反思循环
 */
@Slf4j
@Service
public class ToolResultEvaluator {

    /**
     * 评估工具执行结果的质量
     *
     * @param toolName     工具名
     * @param args         工具参数
     * @param result       工具返回结果
     * @param originalQuery 用户原始查询
     * @return 评估结果
     */
    public Evaluation evaluate(String toolName, String args, String result, String originalQuery) {
        if (result == null || result.isBlank()) {
            return new Evaluation(ResultQuality.ERROR, "工具返回为空", suggestFix(toolName, args, "empty"));
        }

        // 搜索类工具：结果为空或未找到
        if (isSearchTool(toolName)) {
            if (result.contains("未找到") || result.contains("没有找到") || result.contains("无相关")) {
                return new Evaluation(ResultQuality.POOR,
                        "搜索无结果",
                        suggestFix(toolName, args, "no_result"));
            }
            // 结果太短（可能匹配度低）
            if (result.length() < 30) {
                return new Evaluation(ResultQuality.POOR,
                        "搜索结果过少",
                        suggestFix(toolName, args, "too_few"));
            }
        }

        // 执行失败
        if (result.contains("失败") || result.contains("错误") || result.contains("异常")) {
            return new Evaluation(ResultQuality.ERROR, "工具执行出错", "请检查参数后重试");
        }

        return new Evaluation(ResultQuality.GOOD, null, null);
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

    private boolean isSearchTool(String toolName) {
        return "searchNotes".equals(toolName) || "ragSummary".equals(toolName)
                || "getRelatedNotes".equals(toolName);
    }

    private String suggestFix(String toolName, String args, String problemType) {
        return switch (problemType) {
            case "empty" -> "尝试用更简短的关键词重试";
            case "no_result" -> "换个关键词重试，比如去掉修饰词、用更通用的表述";
            case "too_few" -> "尝试用更宽泛的关键词搜索";
            default -> "请检查参数后重试";
        };
    }

    /**
     * 评估结果
     */
    public record Evaluation(ResultQuality quality, String reason, String suggestion) {}
}
