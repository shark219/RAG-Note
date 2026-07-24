package com.rag.notebook.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * 动作观察结果。
 *
 * 每次工具调用后，不直接把原始字符串扔给 LLM，
 * 而是包装成结构化的 Observation：
 *   - 工具做了什么
 *   - 成功/失败/空结果
 *   - 关键数据的结构化提取
 *
 * Observation 进入 AgentState.observations 列表，
 * 形成 Agent 的"环境感知"，驱动下一轮决策。
 */
public record Observation(
        String action,
        String status,
        String summary,
        Map<String, Object> data
) {
    public Observation(String action, String status, String summary) {
        this(action, status, summary, new HashMap<>());
    }

    public Observation withData(String key, Object value) {
        Map<String, Object> d = new HashMap<>(data);
        d.put(key, value);
        return new Observation(action, status, summary, d);
    }

    public boolean isSuccess() { return "success".equals(status); }
    public boolean isEmpty() { return "empty".equals(status); }
    public boolean isError() { return "error".equals(status); }
}
