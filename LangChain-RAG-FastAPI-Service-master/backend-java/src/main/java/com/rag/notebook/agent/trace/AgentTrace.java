package com.rag.notebook.agent.trace;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Agent 执行全链路追踪实体
 */
@Data
@Entity
@Table(name = "agent_traces", indexes = {
        @Index(name = "idx_task_id", columnList = "task_id"),
        @Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_created_at", columnList = "created_at")
})
public class AgentTrace {

    @Id
    @Column(name = "trace_id", length = 36)
    private String traceId;

    @Column(name = "task_id", length = 36, nullable = false)
    private String taskId;

    @Column(name = "session_id", length = 36)
    private String sessionId;

    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(name = "query", columnDefinition = "TEXT")
    private String query;

    // ========== 模型配置 ==========
    @Column(name = "model_name", length = 50)
    private String modelName;

    @Column(name = "temperature")
    private Float temperature;

    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    // ========== 执行统计 ==========
    @Column(name = "loop_count")
    private Integer loopCount = 0;

    @Column(name = "tool_call_count")
    private Integer toolCallCount = 0;

    @Column(name = "total_latency_ms")
    private Long totalLatencyMs;

    // ========== Token 统计 ==========
    @Column(name = "total_tokens")
    private Integer totalTokens = 0;

    @Column(name = "system_prompt_tokens")
    private Integer systemPromptTokens;

    @Column(name = "history_tokens")
    private Integer historyTokens;

    @Column(name = "tool_result_tokens")
    private Integer toolResultTokens;

    // ========== 决策链路（JSON） ==========
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "intent_recognition", columnDefinition = "JSON")
    private Map<String, Object> intentRecognition;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls", columnDefinition = "JSON")
    private String toolCalls;

    // ========== 规划与反思 ==========
    @Column(name = "replanning_triggered")
    private Boolean replanningTriggered = false;

    @Column(name = "replanning_reason", columnDefinition = "TEXT")
    private String replanningReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "original_plan", columnDefinition = "JSON")
    private String originalPlan;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "revised_plan", columnDefinition = "JSON")
    private String revisedPlan;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reflection_records", columnDefinition = "JSON")
    private String reflectionRecords;

    // ========== 关联外部 Trace ==========
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rag_trace_ids", columnDefinition = "JSON")
    private String ragTraceIds;

    // ========== LLM 交互记录（可选，大数据量） ==========
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "llm_interactions", columnDefinition = "JSON")
    private String llmInteractions;

    // ========== 性能指标 ==========
    @Column(name = "planning_latency_ms")
    private Long planningLatencyMs;

    @Column(name = "execution_latency_ms")
    private Long executionLatencyMs;

    @Column(name = "reflection_latency_ms")
    private Long reflectionLatencyMs;

    // ========== 异常记录 ==========
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "errors", columnDefinition = "JSON")
    private String errors;

    // ========== 最终输出 ==========
    @Column(name = "final_answer", columnDefinition = "TEXT")
    private String finalAnswer;

    @Column(name = "status", length = 20)
    private String status; // RUNNING, COMPLETED, FAILED, TIMEOUT

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "artifacts", columnDefinition = "JSON")
    private String artifacts;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
