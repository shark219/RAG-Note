package com.rag.notebook.agent.runtime;

public enum AgentTaskStatus {
    CREATED,
    PLANNING,
    RUNNING,
    WAITING_USER,
    REFLECTING,
    REPLANNING,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED
}
