package com.rag.notebook.evaluation.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "evaluation_reports")
public class EvaluationReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "trace_id", length = 36, nullable = false)
    private String traceId;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "query", columnDefinition = "TEXT")
    private String query;

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

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
