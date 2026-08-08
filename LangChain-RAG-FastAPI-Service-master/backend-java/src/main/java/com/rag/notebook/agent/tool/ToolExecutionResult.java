package com.rag.notebook.agent.tool;

import com.rag.notebook.agent.ToolResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具执行结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionResult {
    /**
     * 执行是否成功
     */
    private boolean success;

    /**
     * 结果内容
     */
    private String result;

    /**
     * 执行延迟（毫秒）
     */
    private long latencyMs;

    /**
     * 错误码
     */
    private String errorCode;

    /**
     * 错误消息
     */
    private String errorMessage;

    /**
     * 工具结果对象
     */
    private ToolResult toolResult;

    public static ToolExecutionResult success(String result, long latencyMs, ToolResult toolResult) {
        return new ToolExecutionResult(true, result, latencyMs, null, null, toolResult);
    }

    public static ToolExecutionResult error(String errorCode, String errorMessage, long latencyMs) {
        return new ToolExecutionResult(false, null, latencyMs, errorCode, errorMessage, null);
    }
}
