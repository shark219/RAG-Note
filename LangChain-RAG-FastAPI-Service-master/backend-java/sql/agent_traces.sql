-- Agent Trace 全链路追踪表
CREATE TABLE IF NOT EXISTS agent_traces (
    trace_id VARCHAR(36) PRIMARY KEY COMMENT 'Trace 唯一标识',
    task_id VARCHAR(36) NOT NULL COMMENT '关联的任务 ID',
    session_id VARCHAR(36) NOT NULL COMMENT '会话 ID',
    user_id VARCHAR(36) NOT NULL COMMENT '用户 ID',
    query TEXT NOT NULL COMMENT '用户原始查询',

    -- 模型配置
    model_name VARCHAR(100) COMMENT '模型名称（如 gpt-4o）',
    temperature FLOAT COMMENT '温度参数',
    system_prompt TEXT COMMENT 'System Prompt 内容',

    -- 执行统计
    loop_count INT DEFAULT 0 COMMENT 'AgentLoop 循环次数',
    tool_call_count INT DEFAULT 0 COMMENT '工具调用总次数',
    total_latency_ms BIGINT COMMENT '总耗时（毫秒）',
    planning_latency_ms BIGINT COMMENT '规划耗时（毫秒）',

    -- Token 统计
    total_tokens INT DEFAULT 0 COMMENT '总 Token 消耗',
    system_prompt_tokens INT DEFAULT 0 COMMENT 'System Prompt Token 数',
    history_tokens INT DEFAULT 0 COMMENT '历史消息 Token 数',
    tool_result_tokens INT DEFAULT 0 COMMENT '工具结果 Token 数',

    -- 决策链路（JSON）
    intent_recognition JSON COMMENT '意图识别结果',
    planning_result JSON COMMENT '规划结果（子任务列表）',
    tool_calls JSON COMMENT '工具调用详细记录 [{step, tool, input, output, latency, success, error}]',

    -- 反思与重规划
    reflection_triggered BOOLEAN DEFAULT FALSE COMMENT '是否触发反思',
    reflection_summary TEXT COMMENT '反思总结',
    reflection_lessons TEXT COMMENT '经验教训',
    reflection_adjustments TEXT COMMENT '调整建议',

    replanning_triggered BOOLEAN DEFAULT FALSE COMMENT '是否触发重规划',
    replanning_reason TEXT COMMENT '重规划原因',
    original_plan JSON COMMENT '原始计划',
    revised_plan JSON COMMENT '修订后计划',

    -- 性能指标（JSON）
    performance_metrics JSON COMMENT '各阶段耗时分解 {retrieval_ms, reasoning_ms, tool_execution_ms}',

    -- 异常记录（JSON 数组）
    errors JSON COMMENT '异常事件列表 [{type, message, tool, timestamp}]',

    -- LLM 交互记录（JSON 数组）
    llm_interactions JSON COMMENT 'LLM 交互历史 [{round, prompt, response, tokens}]',

    -- 最终输出
    final_answer TEXT COMMENT '最终返回给用户的答案',
    final_status VARCHAR(50) COMMENT '最终状态（COMPLETED/FAILED/BLOCKED/WAITING_USER）',

    -- 引用外部 trace
    rag_trace_ids JSON COMMENT '关联的 RAG Trace ID 列表',

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    INDEX idx_task_id (task_id),
    INDEX idx_session_id (session_id),
    INDEX idx_user_id (user_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 全链路追踪表';
