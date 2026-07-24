package com.rag.notebook.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 执行状态：记录工具调用历史和反思信息
 */
public class AgentState {

    private final String originalQuery;
    private final List<ToolCallRecord> toolHistory = new ArrayList<>();
    private String bestAnswer;

    public AgentState(String originalQuery) {
        this.originalQuery = originalQuery;
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

    /**
     * 检查某个工具是否已经调用过（避免重复调用）
     */
    public boolean hasCalledTool(String toolName) {
        return toolHistory.stream().anyMatch(r -> r.toolName().equals(toolName));
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
     * 工具调用记录
     */
    public record ToolCallRecord(String toolName, String args, String result, ResultQuality quality) {}

    /**
     * 工具结果质量
     */
    public enum ResultQuality {
        GOOD,   // 结果足够好
        POOR,   // 结果不理想，需要重试或换策略
        ERROR   // 执行失败
    }
}
