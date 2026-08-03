package com.rag.notebook.evaluation.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "ablation_results")
public class AblationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 实验编号: BASELINE, R-1, R-2, ... */
    @Column(name = "experiment_id", length = 20, nullable = false)
    private String experimentId;

    /** 实验名称 */
    @Column(name = "experiment_name", length = 100)
    private String experimentName;

    /** 被消融的组件名 */
    @Column(name = "ablation_component", length = 50)
    private String ablationComponent;

    /** 关联的 run_id */
    @Column(name = "run_id", length = 36)
    private String runId;

    // ===== RAGAS 四项指标 =====

    @Column(name = "faithfulness")
    private Double faithfulness;

    @Column(name = "answer_relevancy")
    private Double answerRelevancy;

    @Column(name = "context_precision")
    private Double contextPrecision;

    @Column(name = "context_recall")
    private Double contextRecall;

    /** 加权综合得分 */
    @Column(name = "composite_score")
    private Double compositeScore;

    // ===== 与基线的对比 =====

    @Column(name = "baseline_score")
    private Double baselineScore;

    @Column(name = "delta_score")
    private Double deltaScore;

    // ===== 性能指标 =====

    @Column(name = "avg_latency_ms")
    private Long avgLatencyMs;

    @Column(name = "avg_retrieval_latency_ms")
    private Long avgRetrievalLatencyMs;

    @Column(name = "avg_doc_count")
    private Double avgDocCount;

    /** 平均 Query Expansion Token */
    @Column(name = "avg_query_expansion_tokens")
    private Integer avgQueryExpansionTokens;

    /** 平均 Reranker Token */
    @Column(name = "avg_reranker_tokens")
    private Integer avgRerankerTokens;

    /** 平均 Generation Token */
    @Column(name = "avg_generation_tokens")
    private Integer avgGenerationTokens;

    // ===== 元数据 =====

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "test_case_count")
    private Integer testCaseCount;

    @Column(name = "key_findings", columnDefinition = "TEXT")
    private String keyFindings;

    @Column(name = "config_snapshot", columnDefinition = "TEXT")
    private String configSnapshot;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
