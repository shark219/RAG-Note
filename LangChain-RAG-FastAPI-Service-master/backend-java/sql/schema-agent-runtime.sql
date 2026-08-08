CREATE TABLE IF NOT EXISTS agent_tasks (
    task_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    original_query TEXT NOT NULL,
    normalized_goal TEXT NULL,
    status VARCHAR(32) NOT NULL,
    execution_mode VARCHAR(32) NULL,
    iteration_count INT DEFAULT 0,
    tool_call_count INT DEFAULT 0,
    token_consumed INT DEFAULT 0,
    current_plan_snapshot TEXT NULL,
    current_summary TEXT NULL,
    final_answer TEXT NULL,
    failure_reason TEXT NULL,
    next_run_at DATETIME NULL,
    started_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_agent_tasks_session_id (session_id),
    KEY idx_agent_tasks_user_id (user_id),
    KEY idx_agent_tasks_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_task_steps (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    step_id VARCHAR(64) NOT NULL,
    label VARCHAR(255) NULL,
    goal TEXT NULL,
    status VARCHAR(32) NOT NULL,
    sequence_no INT NULL,
    depends_on_json TEXT NULL,
    success_criteria_json TEXT NULL,
    result_summary TEXT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_agent_task_steps_task_id (task_id),
    KEY idx_agent_task_steps_step_id (step_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_task_events (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json TEXT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_agent_task_events_task_id (task_id),
    KEY idx_agent_task_events_event_type (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
