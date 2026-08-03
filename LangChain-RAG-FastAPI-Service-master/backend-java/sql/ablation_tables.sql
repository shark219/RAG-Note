-- ============================================
-- RAG-Note 消融实验数据库变更方案
-- 用于手动执行或参考，Hibernate ddl-auto=update 会自动处理
-- ============================================

-- ============================================
-- 1. 新建 ablation_runs 表（实验运行记录）
-- ============================================
CREATE TABLE IF NOT EXISTS ablation_runs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL COMMENT '每次 run-all 生成的唯一标识',
    user_id VARCHAR(36) COMMENT '用户ID',
    status VARCHAR(20) DEFAULT 'CREATED' COMMENT 'CREATED / RUNNING / SUCCESS / FAILED / CANCELLED',
    started_at DATETIME COMMENT '开始时间',
    finished_at DATETIME COMMENT '完成时间',
    total_cases INT COMMENT '测试用例总数',
    success_cases INT COMMENT '成功用例数',
    fixed_config TEXT COMMENT '固定参数快照（JSON）',
    question_snapshot TEXT COMMENT '本次 run 使用的测试用例 question 列表（JSON）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_run_id (run_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消融实验运行记录表';

-- ============================================
-- 2. 新建 ablation_evaluation_reports 表（隔离存储消融评估结果）
--    结构同 evaluation_reports + run_id + 分阶段 token
-- ============================================
CREATE TABLE IF NOT EXISTS ablation_evaluation_reports (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL COMMENT '关联的 run_id',
    experiment_id VARCHAR(20) NOT NULL COMMENT '实验编号: BASELINE, R-1, R-2, ...',
    question_id BIGINT COMMENT '关联的测试用例 id',
    trace_id VARCHAR(36) COMMENT 'Trace ID',
    user_id VARCHAR(36) COMMENT '用户ID',
    query TEXT COMMENT '用户查询',
    answer TEXT COMMENT 'RAG 回答',

    -- RAGAS 四项指标
    context_precision DOUBLE COMMENT '上下文精度',
    context_recall DOUBLE COMMENT '上下文召回率',
    faithfulness DOUBLE COMMENT '忠实度',
    answer_relevancy DOUBLE COMMENT '回答相关性',
    rule_score INT COMMENT '规则评分',
    total_score INT COMMENT '综合评分',
    harmonic_mean DOUBLE COMMENT '调和平均数',
    level VARCHAR(16) COMMENT '评级: 优秀/良好/及格/不及格',
    diagnosis TEXT COMMENT '诊断结果',

    -- 分阶段 Token 统计
    retrieval_tokens INT DEFAULT 0 COMMENT '检索阶段 Token',
    query_expansion_tokens INT DEFAULT 0 COMMENT '查询扩展 Token',
    reranker_tokens INT DEFAULT 0 COMMENT '重排序 Token',
    generation_tokens INT DEFAULT 0 COMMENT '生成 Token',
    total_tokens INT DEFAULT 0 COMMENT '总 Token',

    -- 延时
    latency_ms BIGINT COMMENT '总延迟(ms)',
    retrieval_latency_ms BIGINT COMMENT '检索延迟(ms)',

    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_run_id (run_id),
    INDEX idx_experiment_id (experiment_id),
    INDEX idx_question_id (question_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消融实验评估报告表（隔离存储，不污染 evaluation_reports）';

-- ============================================
-- 3. 修改现有 ablation_results 表（新字段允许为空，兼容旧数据）
-- ============================================
ALTER TABLE ablation_results
    ADD COLUMN IF NOT EXISTS run_id VARCHAR(36) COMMENT '关联的 run_id' AFTER ablation_component,
    ADD COLUMN IF NOT EXISTS avg_token_consumed INT COMMENT '平均 Token 消耗' AFTER avg_doc_count,
    ADD COLUMN IF NOT EXISTS avg_query_expansion_tokens INT COMMENT '平均 Query Expansion Token' AFTER avg_token_consumed,
    ADD COLUMN IF NOT EXISTS avg_reranker_tokens INT COMMENT '平均 Reranker Token' AFTER avg_query_expansion_tokens,
    ADD COLUMN IF NOT EXISTS avg_generation_tokens INT COMMENT '平均 Generation Token' AFTER avg_reranker_tokens;

-- MySQL 不支持 ADD COLUMN IF NOT EXISTS，如执行报错请改为逐列判断：

-- -- 添加 run_id
-- SET @stmt = (SELECT IF(
--     (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ablation_results' AND COLUMN_NAME = 'run_id') = 0,
--     'ALTER TABLE ablation_results ADD COLUMN run_id VARCHAR(36) COMMENT ''关联的 run_id'' AFTER ablation_component',
--     'SELECT ''column run_id already exists'''
-- ));
-- PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- -- 添加 avg_query_expansion_tokens
-- SET @stmt = (SELECT IF(
--     (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ablation_results' AND COLUMN_NAME = 'avg_query_expansion_tokens') = 0,
--     'ALTER TABLE ablation_results ADD COLUMN avg_query_expansion_tokens INT COMMENT ''平均 Query Expansion Token'' AFTER avg_token_consumed',
--     'SELECT ''column avg_query_expansion_tokens already exists'''
-- ));
-- PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- -- 添加 avg_reranker_tokens
-- SET @stmt = (SELECT IF(
--     (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ablation_results' AND COLUMN_NAME = 'avg_reranker_tokens') = 0,
--     'ALTER TABLE ablation_results ADD COLUMN avg_reranker_tokens INT COMMENT ''平均 Reranker Token'' AFTER avg_query_expansion_tokens',
--     'SELECT ''column avg_reranker_tokens already exists'''
-- ));
-- PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- -- 添加 avg_generation_tokens
-- SET @stmt = (SELECT IF(
--     (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ablation_results' AND COLUMN_NAME = 'avg_generation_tokens') = 0,
--     'ALTER TABLE ablation_results ADD COLUMN avg_generation_tokens INT COMMENT ''平均 Generation Token'' AFTER avg_reranker_tokens',
--     'SELECT ''column avg_generation_tokens already exists'''
-- ));
-- PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
