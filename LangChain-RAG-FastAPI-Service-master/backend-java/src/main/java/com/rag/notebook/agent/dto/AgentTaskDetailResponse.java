package com.rag.notebook.agent.dto;

import lombok.Data;

import java.util.List;

@Data
public class AgentTaskDetailResponse {
    private String taskId;
    private String sessionId;
    private String status;
    private String executionMode;
    private String originalQuery;
    private String normalizedGoal;
    private Integer iterationCount;
    private Integer toolCallCount;
    private Integer tokenConsumed;
    private String currentSummary;
    private String finalAnswer;
    private String failureReason;
    private Boolean resumable;
    private String nextStepId;
    private Integer remainingStepCount;
    private List<String> remainingStepLabels;
    private List<StepItem> steps;
    private List<EventItem> events;

    @Data
    public static class StepItem {
        private String stepId;
        private String label;
        private String goal;
        private String status;
        private Integer sequenceNo;
        private String resultSummary;
    }

    @Data
    public static class EventItem {
        private String eventType;
        private String payloadJson;
        private String createdAt;
    }
}
