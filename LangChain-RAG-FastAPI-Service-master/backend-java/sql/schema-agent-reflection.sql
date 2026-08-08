CREATE TABLE IF NOT EXISTS agent_reflections (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    step_id VARCHAR(64) NULL,
    iteration INT NOT NULL,
    goal_achieved BOOLEAN DEFAULT FALSE,
    should_replan BOOLEAN DEFAULT FALSE,
    should_ask_user BOOLEAN DEFAULT FALSE,
    failure_type VARCHAR(64) NULL,
    root_cause TEXT NULL,
    missing_evidence_json TEXT NULL,
    recommended_actions_json TEXT NULL,
    summary TEXT NULL,
    confidence DOUBLE NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_agent_reflections_task_id (task_id),
    KEY idx_agent_reflections_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_tool_metrics (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NULL,
    tool_name VARCHAR(64) NOT NULL,
    success BOOLEAN NOT NULL,
    latency_ms BIGINT NOT NULL,
    error_code VARCHAR(64) NULL,
    error_message TEXT NULL,
    user_id VARCHAR(64) NULL,
    session_id VARCHAR(64) NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_agent_tool_metrics_task_id (task_id),
    KEY idx_agent_tool_metrics_tool_name (tool_name),
    KEY idx_agent_tool_metrics_created_at (created_at),
    KEY idx_agent_tool_metrics_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
