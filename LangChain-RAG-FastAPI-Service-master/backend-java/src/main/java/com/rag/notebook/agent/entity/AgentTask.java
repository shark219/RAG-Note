package com.rag.notebook.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "agent_tasks")
public class AgentTask {

    @Id
    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(name = "original_query", columnDefinition = "TEXT", nullable = false)
    private String originalQuery;

    @Column(name = "normalized_goal", columnDefinition = "TEXT")
    private String normalizedGoal;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "execution_mode", length = 32)
    private String executionMode;

    @Column(name = "iteration_count")
    private Integer iterationCount = 0;

    @Column(name = "tool_call_count")
    private Integer toolCallCount = 0;

    @Column(name = "token_consumed")
    private Integer tokenConsumed = 0;

    @Column(name = "current_plan_snapshot", columnDefinition = "TEXT")
    private String currentPlanSnapshot;

    @Column(name = "current_summary", columnDefinition = "TEXT")
    private String currentSummary;

    @Column(name = "final_answer", columnDefinition = "TEXT")
    private String finalAnswer;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @CreationTimestamp
    @Column(name = "started_at", updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
