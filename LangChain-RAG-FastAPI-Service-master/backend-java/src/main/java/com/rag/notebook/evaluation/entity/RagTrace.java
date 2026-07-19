package com.rag.notebook.evaluation.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@Entity
@Table(name = "rag_traces")
public class RagTrace {

    @Id
    @Column(name = "trace_id", length = 36)
    private String traceId;

    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(name = "query", columnDefinition = "TEXT", nullable = false)
    private String query;

    @Column(name = "hyde_doc", columnDefinition = "TEXT")
    private String hydeDoc;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expanded_queries", columnDefinition = "JSON")
    private List<String> expandedQueries;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieved_docs", columnDefinition = "JSON")
    private List<String> retrievedDocs;

    @Column(name = "final_answer", columnDefinition = "TEXT")
    private String finalAnswer;

    @Column(name = "ground_truth", columnDefinition = "TEXT")
    private String groundTruth;

    @Column(name = "total_latency_ms")
    private Long totalLatencyMs;

    @Column(name = "retrieval_latency_ms")
    private Long retrievalLatencyMs;

    @Column(name = "generation_latency_ms")
    private Long generationLatencyMs;

    @Column(name = "token_consumed")
    private Integer tokenConsumed;

    @Column(name = "retrieved_doc_count")
    private Integer retrievedDocCount;

    @Column(name = "avg_similarity")
    private Double avgSimilarity;

    @Column(name = "user_feedback")
    private Integer userFeedback;

    @Column(name = "feedback_reason", length = 64)
    private String feedbackReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
