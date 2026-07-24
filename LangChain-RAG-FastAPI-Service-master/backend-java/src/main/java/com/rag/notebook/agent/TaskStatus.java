package com.rag.notebook.agent;

/**
 * 任务状态机：让 Agent 知道"我在哪一步、下一步该做什么"。
 *
 * 核心思想：Agent 不应该自己猜是否完成，而是由状态机明确告知。
 *
 * 状态流转：
 *   CREATED → EXECUTING → GOAL_ACHIEVED → COMPLETED
 *   CREATED → EXECUTING → GOAL_FAILED
 *
 * 每个工具调用后，GoalEvaluator 检查状态：
 *   - 所有 requiredArtifacts 已产出 → GOAL_ACHIEVED
 *   - 有不可恢复的失败 → GOAL_FAILED
 *   - 否则继续 → EXECUTING
 */
public enum TaskStatus {

    /** 初始状态：任务刚创建，尚未开始执行 */
    CREATED,

    /** 执行中：Agent 正在调用工具，目标尚未达成 */
    EXECUTING,

    /** 目标已达成：所有 requiredArtifacts 已产出，无需再调工具 */
    GOAL_ACHIEVED,

    /** 目标失败：无法达成目标（如资源不存在、权限不足等不可恢复错误） */
    GOAL_FAILED,

    /** 最终完成：已生成最终回答，任务结束 */
    COMPLETED
}
