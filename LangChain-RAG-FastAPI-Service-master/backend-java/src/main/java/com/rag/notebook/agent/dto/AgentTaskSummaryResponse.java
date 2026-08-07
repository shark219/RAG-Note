package com.rag.notebook.agent.dto;

import lombok.Data;

public class AgentTaskSummaryResponse {

    @Data
    public static class Item {
        private String taskId;
        private String sessionId;
        private String status;
        private String executionMode;
        private String originalQuery;
        private String normalizedGoal;
        private Integer iterationCount;
        private Integer toolCallCount;
        private String currentSummary;
        private Boolean resumable;
        private String startedAt;
        private String finishedAt;
    }
}
