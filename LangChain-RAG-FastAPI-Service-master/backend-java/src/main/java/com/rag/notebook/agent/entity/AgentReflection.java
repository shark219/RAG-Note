package com.rag.notebook.agent.entity;

import lombok.Data;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Agent 反思记录
 */
@Data
@Entity
@Table(name = "agent_reflections")
public class AgentReflection {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "task_id", length = 64, nullable = false)
    private String taskId;

    @Column(name = "step_id", length = 64)
    private String stepId;

    @Column(nullable = false)
    private Integer iteration;

    @Column(name = "goal_achieved")
    private Boolean goalAchieved;

    @Column(name = "should_replan")
    private Boolean shouldReplan;

    @Column(name = "should_ask_user")
    private Boolean shouldAskUser;

    @Column(name = "failure_type", length = 64)
    private String failureType;

    @Column(name = "root_cause", columnDefinition = "TEXT")
    private String rootCause;

    @Column(name = "missing_evidence_json", columnDefinition = "TEXT")
    private String missingEvidenceJson;

    @Column(name = "recommended_actions_json", columnDefinition = "TEXT")
    private String recommendedActionsJson;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column
    private Double confidence;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
