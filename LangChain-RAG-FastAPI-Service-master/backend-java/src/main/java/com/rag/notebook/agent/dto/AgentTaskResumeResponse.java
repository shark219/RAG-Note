package com.rag.notebook.agent.dto;

import lombok.Data;

import java.util.List;

@Data
public class AgentTaskResumeResponse {
    private String taskId;
    private String sessionId;
    private String status;
    private String executionMode;
    private String resumedQuery;
    private String resumedSummary;
    private String nextStepId;
    private Integer remainingStepCount;
    private List<String> remainingStepLabels;
}
