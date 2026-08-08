-- ============================================
-- Agent 完整数据库表（合并去重版）
-- 执行环境：当前项目数据库
-- 作用：支持 Agent 任务持久化、反思、工具指标
-- ============================================

-- 1. Agent 任务表（核心）
CREATE TABLE IF NOT EXISTS agent_tasks (
    task_id VARCHAR(64) PRIMARY KEY COMMENT '任务唯一标识',
    user_id VARCHAR(64) NOT NULL COMMENT '用户ID',
    session_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    original_query TEXT NOT NULL COMMENT '用户原始查询',
    normalized_goal TEXT NULL COMMENT '规范化后的目标',
    status VARCHAR(32) NOT NULL COMMENT '任务状态: CREATED/PLANNING/RUNNING/WAITING_USER/COMPLETED/BLOCKED/FAILED',
    execution_mode VARCHAR(32) NULL COMMENT '执行模式: SINGLE/SEQUENTIAL/PARALLEL',
    iteration_count INT DEFAULT 0 COMMENT '已执行轮次',
    tool_call_count INT DEFAULT 0 COMMENT '工具调用次数',
    token_consumed INT DEFAULT 0 COMMENT '消耗Token数',
    current_plan_snapshot TEXT NULL COMMENT '当前计划快照（JSON）',
    current_summary TEXT NULL COMMENT '当前状态摘要',
    final_answer TEXT NULL COMMENT '最终答案',
    failure_reason TEXT NULL COMMENT '失败原因',
    next_run_at DATETIME NULL COMMENT '下次运行时间（用于延迟任务）',
    started_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
    finished_at DATETIME NULL COMMENT '完成时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY idx_agent_tasks_session_id (session_id),
    KEY idx_agent_tasks_user_id (user_id),
    KEY idx_agent_tasks_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent任务表';

-- 2. Agent 任务步骤表
CREATE TABLE IF NOT EXISTS agent_task_steps (
    id VARCHAR(64) PRIMARY KEY COMMENT '步骤唯一标识',
    task_id VARCHAR(64) NOT NULL COMMENT '所属任务ID',
    step_id VARCHAR(64) NOT NULL COMMENT '步骤ID（如 S-1, S-2）',
    label VARCHAR(255) NULL COMMENT '步骤标签',
    goal TEXT NULL COMMENT '步骤目标',
    status VARCHAR(32) NOT NULL COMMENT '步骤状态',
    sequence_no INT NULL COMMENT '执行序号',
    depends_on_json TEXT NULL COMMENT '依赖步骤（JSON数组）',
    success_criteria_json TEXT NULL COMMENT '成功标准（JSON数组）',
    result_summary TEXT NULL COMMENT '执行结果摘要',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_agent_task_steps_task_id (task_id),
    KEY idx_agent_task_steps_step_id (step_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent任务步骤表';

-- 3. Agent 任务事件表
CREATE TABLE IF NOT EXISTS agent_task_events (
    id VARCHAR(64) PRIMARY KEY COMMENT '事件唯一标识',
    task_id VARCHAR(64) NOT NULL COMMENT '所属任务ID',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型: TASK_CREATED/PLAN_CREATED/STEP_STARTED/TOOL_CALLED等',
    payload_json TEXT NULL COMMENT '事件负载（JSON）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '事件时间',
    KEY idx_agent_task_events_task_id (task_id),
    KEY idx_agent_task_events_event_type (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent任务事件表（事件时间线）';

-- 4. Agent 反思记录表
CREATE TABLE IF NOT EXISTS agent_reflections (
    id VARCHAR(64) PRIMARY KEY COMMENT '反思记录唯一标识',
    task_id VARCHAR(64) NOT NULL COMMENT '所属任务ID',
    step_id VARCHAR(64) NULL COMMENT '所属步骤ID',
    iteration INT NOT NULL COMMENT '执行轮次',
    goal_achieved BOOLEAN DEFAULT FALSE COMMENT '目标是否达成',
    should_replan BOOLEAN DEFAULT FALSE COMMENT '是否需要重规划',
    should_ask_user BOOLEAN DEFAULT FALSE COMMENT '是否需要询问用户',
    failure_type VARCHAR(64) NULL COMMENT '失败类型: NO_PROGRESS/REPEATED_TOOL_FAILURE/MISSING_EVIDENCE/WRONG_STRATEGY',
    root_cause TEXT NULL COMMENT '根本原因',
    missing_evidence_json TEXT NULL COMMENT '缺失证据（JSON数组）',
    recommended_actions_json TEXT NULL COMMENT '推荐行动（JSON数组）',
    summary TEXT NULL COMMENT '反思总结',
    confidence DOUBLE NULL COMMENT '置信度（0-1）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_agent_reflections_task_id (task_id),
    KEY idx_agent_reflections_task_step (task_id, step_id),
    KEY idx_agent_reflections_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent反思记录表';

-- 5. Agent 工具执行指标表
CREATE TABLE IF NOT EXISTS agent_tool_metrics (
    id VARCHAR(64) PRIMARY KEY COMMENT '指标记录唯一标识',
    task_id VARCHAR(64) NULL COMMENT '所属任务ID（可为空，支持独立工具调用）',
    tool_name VARCHAR(64) NOT NULL COMMENT '工具名称',
    success BOOLEAN NOT NULL COMMENT '是否成功',
    latency_ms BIGINT NOT NULL COMMENT '执行耗时（毫秒）',
    error_code VARCHAR(64) NULL COMMENT '错误码',
    error_message TEXT NULL COMMENT '错误消息',
    user_id VARCHAR(64) NULL COMMENT '用户ID',
    session_id VARCHAR(64) NULL COMMENT '会话ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_agent_tool_metrics_task_id (task_id),
    KEY idx_agent_tool_metrics_tool_name (tool_name),
    KEY idx_agent_tool_metrics_user_id (user_id),
    KEY idx_agent_tool_metrics_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent工具执行指标表';
