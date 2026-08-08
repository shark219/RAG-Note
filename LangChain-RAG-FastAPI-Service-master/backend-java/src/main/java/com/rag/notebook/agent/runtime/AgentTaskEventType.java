package com.rag.notebook.agent.runtime;

public enum AgentTaskEventType {
    TASK_CREATED,
    PLAN_CREATED,
    STEP_STARTED,
    STEP_SKIPPED,
    TOOL_CALLED,
    TOOL_SUCCEEDED,
    TOOL_FAILED,
    REFLECTION_CREATED,
    REPLAN_CREATED,
    TASK_BLOCKED,
    TASK_COMPLETED,
    TASK_FAILED
}
