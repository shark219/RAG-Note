package com.rag.notebook.agent.runtime;

import com.rag.notebook.agent.SubTask;

import java.util.List;

public record AgentResumeContext(
        String taskId,
        String sessionId,
        String userId,
        String resumedQuery,
        String resumedSummary,
        String executionMode,
        String stepId,
        List<SubTask> remainingSteps
) {
}
