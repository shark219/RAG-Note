package com.rag.notebook.agent.entity;

import lombok.Data;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Agent 工具执行指标
 */
@Data
@Entity
@Table(name = "agent_tool_metrics")
public class AgentToolMetric {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "tool_name", length = 64, nullable = false)
    private String toolName;

    @Column(nullable = false)
    private Boolean success;

    @Column(name = "latency_ms", nullable = false)
    private Long latencyMs;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
