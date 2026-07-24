package com.rag.notebook.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 可操作的资源。
 *
 * 代表 Agent 环境中已存在的东西——笔记、知识库文档、URL 等。
 * Agent 可以读取、编辑、删除、或以这些资源为基础生成产物。
 */
public record Resource(
        String type,
        String id,
        String label,
        Map<String, Object> metadata
) {
    public Resource(String type, String id, String label) {
        this(type, id, label, new HashMap<>());
    }

    public Resource withMeta(String key, Object value) {
        Map<String, Object> m = new HashMap<>(metadata);
        m.put(key, value);
        return new Resource(type, id, label, m);
    }
}
