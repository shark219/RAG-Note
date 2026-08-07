package com.rag.notebook.agent.runtime;

import com.rag.notebook.agent.SubTask;

import java.util.List;

public record ReplanResult(
        String replanReason,
        List<SubTask> newSteps,
        boolean replaceRemainingPlan,
        String summary
) {
    public static ReplanResult noOp() {
        return new ReplanResult(null, List.of(), false, null);
    }
}
