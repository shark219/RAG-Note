package com.rag.notebook.agent.runtime;

import com.rag.notebook.agent.ToolResult;

/**
 * 工具执行结果封装
 */
public class ToolExecutionResult {
    private final boolean success;
    private final String result;
    private final String errorMessage;
    private final String errorCode;
    private final ToolResult toolResult;
    private final long executionTimeMs;

    private ToolExecutionResult(boolean success, String result, String errorMessage,
                                String errorCode, ToolResult toolResult, long executionTimeMs) {
        this.success = success;
        this.result = result;
        this.errorMessage = errorMessage;
        this.errorCode = errorCode;
        this.toolResult = toolResult;
        this.executionTimeMs = executionTimeMs;
    }

    public static ToolExecutionResult success(String result, ToolResult toolResult, long executionTimeMs) {
        return new ToolExecutionResult(true, result, null, null, toolResult, executionTimeMs);
    }

    public static ToolExecutionResult failure(String errorMessage, String errorCode, long executionTimeMs) {
        return new ToolExecutionResult(false, null, errorMessage, errorCode, null, executionTimeMs);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getResult() {
        return result;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public ToolResult getToolResult() {
        return toolResult;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }
}
