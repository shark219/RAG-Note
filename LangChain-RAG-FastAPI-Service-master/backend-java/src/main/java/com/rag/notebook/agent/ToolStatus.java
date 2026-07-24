package com.rag.notebook.agent;

/**
 * 工具执行状态枚举
 */
public enum ToolStatus {
    SUCCESS,  // 业务成功（找到了笔记、读取了内容等）
    EMPTY,    // 空结果（搜索无匹配，但执行本身没出错）
    ERROR     // 执行失败（资源不存在、参数错误、系统异常等）
}
