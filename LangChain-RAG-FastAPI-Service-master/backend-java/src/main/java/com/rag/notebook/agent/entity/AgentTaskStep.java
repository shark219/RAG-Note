package com.rag.notebook.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "agent_task_steps")
public class AgentTaskStep {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "task_id", length = 64, nullable = false)
    private String taskId;

    @Column(name = "step_id", length = 64, nullable = false)
    private String stepId;

    @Column(name = "label", length = 255)
    private String label;

    @Column(name = "goal", columnDefinition = "TEXT")
    private String goal;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "sequence_no")
    private Integer sequenceNo;

    @Column(name = "depends_on_json", columnDefinition = "TEXT")
    private String dependsOnJson;

    @Column(name = "success_criteria_json", columnDefinition = "TEXT")
    private String successCriteriaJson;

    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
