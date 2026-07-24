-- ============================================
-- RAG-Note 消融实验结果存储表
-- Hibernate ddl-auto=update 会自动建表，
-- 此文件仅作为参考 DDL
-- ============================================

CREATE TABLE IF NOT EXISTS ablation_results (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    experiment_id VARCHAR(20) NOT NULL COMMENT '实验编号: BASELINE, R-1, R-2, ...',
    experiment_name VARCHAR(100) COMMENT '实验名称',
    ablation_component VARCHAR(50) COMMENT '被消融的组件名',
    -- RAGAS 四项指标
    faithfulness DOUBLE COMMENT '忠实度',
    answer_relevancy DOUBLE COMMENT '回答相关性',
    context_precision DOUBLE COMMENT '上下文精确度',
    context_recall DOUBLE COMMENT '上下文召回率',
    composite_score DOUBLE COMMENT '加权综合得分',
    -- 与基线对比
    baseline_score DOUBLE COMMENT '基线综合得分',
    delta_score DOUBLE COMMENT '与基线的差值',
    -- 性能指标
    avg_latency_ms BIGINT COMMENT '平均总延迟(ms)',
    avg_retrieval_latency_ms BIGINT COMMENT '平均检索延迟(ms)',
    avg_doc_count DOUBLE COMMENT '平均检索文档数',
    -- 元数据
    user_id VARCHAR(36) COMMENT '用户ID',
    test_case_count INT COMMENT '测试用例数',
    key_findings TEXT COMMENT '自动生成的关键发现',
    config_snapshot TEXT COMMENT '实验配置快照(JSON)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_experiment_id (experiment_id),
    INDEX idx_user_id (user_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消融实验结果表';
