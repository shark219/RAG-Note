package com.rag.notebook.agent;

/**
 * 工具执行结果 — 结构化返回，替代 String
 *
 * 让 Agent Runtime 明确知道工具执行状态，不再靠猜字符串。
 */
public record ToolResult(
        ToolStatus status,
        String display,     // 给 LLM 看的自然语言文本
        String errorCode,   // 错误码（仅 ERROR 时有值）
        boolean retryable   // 是否可重试
) {

    /** 成功 */
    public static ToolResult success(String display) {
        return new ToolResult(ToolStatus.SUCCESS, display, null, false);
    }

    /** 空结果（搜索无匹配） */
    public static ToolResult empty(String display) {
        return new ToolResult(ToolStatus.EMPTY, display, null, true);
    }

    /** 业务失败（资源不存在等） */
    public static ToolResult error(String display, String errorCode, boolean retryable) {
        return new ToolResult(ToolStatus.ERROR, display, errorCode, retryable);
    }

    /** 给 LLM 看的文本（兼容旧代码） */
    public String toDisplay() {
        return display;
    }
}
