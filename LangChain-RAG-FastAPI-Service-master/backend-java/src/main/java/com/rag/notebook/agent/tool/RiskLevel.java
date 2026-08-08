package com.rag.notebook.agent.tool;

/**
 * 工具风险级别
 */
public enum RiskLevel {
    /**
     * 低风险：只读操作
     */
    LOW,

    /**
     * 中等风险：创建、编辑操作
     */
    MEDIUM,

    /**
     * 高风险：删除、外部调用
     */
    HIGH,

    /**
     * 极高风险：批量删除、系统操作
     */
    CRITICAL
}
