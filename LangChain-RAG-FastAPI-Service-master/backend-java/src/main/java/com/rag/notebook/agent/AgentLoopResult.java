package com.rag.notebook.agent;

/**
 * AgentLoop 的执行结果。
 *
 * 关键设计：AgentLoop 不生成最终回答文本，只返回"任务是否完成"和状态。
 * 最终回答由 ResponseComposer 根据证据独立生成。
 */
public record AgentLoopResult(
        Outcome outcome,
        AgentState state
) {

    public enum Outcome {
        /** 任务完成，证据充分，可以生成最终回答 */
        READY,
        /** 达到最大轮次，证据可能不足，Composer 需诚实说明 */
        MAX_ROUNDS
    }

    public static AgentLoopResult ready(AgentState state) {
        return new AgentLoopResult(Outcome.READY, state);
    }

    public static AgentLoopResult maxRounds(AgentState state) {
        return new AgentLoopResult(Outcome.MAX_ROUNDS, state);
    }
}
