package com.rag.notebook.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 产出的产物。
 *
 * 与 Resource（已存在的东西）不同，Artifact 是 Agent 在本次任务中**生成的**。
 * 例如：思维导图、Mermaid 图表、新创建的笔记、合并后的笔记。
 *
 * Artifact 是 GoalEvaluator 判断"目标是否达成"的核心依据。
 */
public record Artifact(
        String type,
        String id,
        String label,
        Map<String, Object> metadata
) {
    public Artifact(String type, String id, String label) {
        this(type, id, label, new HashMap<>());
    }

    public Artifact withMeta(String key, Object value) {
        Map<String, Object> m = new HashMap<>(metadata);
        m.put(key, value);
        return new Artifact(type, id, label, m);
    }
}
