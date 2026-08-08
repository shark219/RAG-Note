package com.rag.notebook.agent.policy;

import lombok.Builder;
import lombok.Data;

/**
 * Agent 预算配置
 */
@Data
@Builder
public class BudgetConfig {
    /**
     * 最大迭代次数
     */
    private int maxIterations;

    /**
     * 最大工具调用次数
     */
    private int maxToolCalls;

    /**
     * 最大 Token 消耗
     */
    private int maxTokens;

    /**
     * 最大运行时长（秒）
     */
    private int maxRuntimeSeconds;

    /**
     * 最大连续失败次数
     */
    private int maxConsecutiveFailures;

    /**
     * 同一工具最大调用次数
     */
    private int maxSameToolCalls;
}
