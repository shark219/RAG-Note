package com.rag.notebook.agent.runtime;

import com.rag.notebook.agent.AgentState;

public record AgentExecutionSnapshot(
        int iterationCount,
        int toolCallCount,
        int tokenConsumed,
        String summary,
        String latestObservation,
        String latestFailureReason,
        String lastReflectionSummary,
        AgentState state
) {
}
