package com.rag.notebook.evaluation.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "ablation_runs")
public class AblationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 每次 run-all 生成的唯一标识 */
    @Column(name = "run_id", length = 36, nullable = false)
    private String runId;

    @Column(name = "user_id", length = 36)
    private String userId;

    /** CREATED / RUNNING / SUCCESS / FAILED / CANCELLED */
    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "total_cases")
    private Integer totalCases;

    @Column(name = "success_cases")
    private Integer successCases;

    /** 固定参数快照（embedding / chunk / topK / 测试集等）JSON */
    @Column(name = "fixed_config", columnDefinition = "TEXT")
    private String fixedConfig;

    /** 本次 run 使用的测试用例 question 列表 JSON */
    @Column(name = "question_snapshot", columnDefinition = "TEXT")
    private String questionSnapshot;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
