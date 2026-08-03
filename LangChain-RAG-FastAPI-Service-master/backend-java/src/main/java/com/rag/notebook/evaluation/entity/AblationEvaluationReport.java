package com.rag.notebook.evaluation.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "ablation_evaluation_reports")
public class AblationEvaluationReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的 run_id */
    @Column(name = "run_id", length = 36, nullable = false)
    private String runId;

    /** 实验编号: BASELINE, R-1, R-2, ... */
    @Column(name = "experiment_id", length = 20, nullable = false)
    private String experimentId;

    /** 关联的测试用例 id */
    @Column(name = "question_id")
    private Long questionId;

    @Column(name = "trace_id", length = 36)
    private String traceId;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "query", columnDefinition = "TEXT")
    private String query;

    @Column(name = "answer", columnDefinition = "TEXT")
    private String answer;

    // ===== RAGAS 四项指标 =====

    @Column(name = "context_precision")
    private Double contextPrecision;

    @Column(name = "context_recall")
    private Double contextRecall;

    @Column(name = "faithfulness")
    private Double faithfulness;

    @Column(name = "answer_relevancy")
    private Double answerRelevancy;

    @Column(name = "rule_score")
    private Integer ruleScore;

    @Column(name = "total_score")
    private Integer totalScore;

    @Column(name = "harmonic_mean")
    private Double harmonicMean;

    @Column(name = "level", length = 16)
    private String level;

    @Column(name = "diagnosis", columnDefinition = "TEXT")
    private String diagnosis;

    // ===== 分阶段 Token =====

    @Column(name = "retrieval_tokens")
    private Integer retrievalTokens;

    @Column(name = "query_expansion_tokens")
    private Integer queryExpansionTokens;

    @Column(name = "reranker_tokens")
    private Integer rerankerTokens;

    @Column(name = "generation_tokens")
    private Integer generationTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    // ===== 延时 =====

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "retrieval_latency_ms")
    private Long retrievalLatencyMs;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
