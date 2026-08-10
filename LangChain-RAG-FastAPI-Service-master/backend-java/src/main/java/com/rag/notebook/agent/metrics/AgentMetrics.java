package com.rag.notebook.agent.metrics;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 评估指标
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentMetrics {

    /**
     * 统计周期开始时间
     */
    private String startDate;

    /**
     * 统计周期结束时间
     */
    private String endDate;

    /**
     * 总任务数
     */
    private long totalTasks;

    /**
     * 完成任务数
     */
    private long completedTasks;

    /**
     * 失败任务数
     */
    private long failedTasks;

    /**
     * 阻塞任务数
     */
    private long blockedTasks;

    /**
     * 任务完成率
     */
    private double completionRate;

    /**
     * 平均迭代轮次
     */
    private double avgIterationCount;

    /**
     * 平均工具调用次数
     */
    private double avgToolCallCount;

    /**
     * 平均 Token 消耗
     */
    private double avgTokenConsumed;

    /**
     * 触发反思次数
     */
    private long reflectionCount;

    /**
     * 触发重规划次数
     */
    private long replanCount;

    /**
     * URL 白名单拦截次数
     */
    private long urlBlockedCount;

    /**
     * 工具执行总次数
     */
    private long totalToolExecutions;

    /**
     * 工具执行成功次数
     */
    private long successfulToolExecutions;

    /**
     * 工具执行成功率
     */
    private double toolSuccessRate;

    /**
     * 工具平均耗时（毫秒）
     */
    private double avgToolLatencyMs;
}
