package com.rag.notebook.agent.runtime;

import java.util.List;

public record ReflectionResult(
        boolean goalAchieved,
        boolean shouldReplan,
        boolean shouldAskUser,
        String failureType,
        String rootCause,
        List<String> missingEvidence,
        List<String> recommendedActions,
        String summary,
        Double confidence
) {
    public static ReflectionResult noOp() {
        return new ReflectionResult(false, false, false, null, null, List.of(), List.of(), null, null);
    }
}
