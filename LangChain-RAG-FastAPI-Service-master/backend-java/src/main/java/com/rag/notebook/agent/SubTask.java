package com.rag.notebook.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Supervisor 拆分的子任务。
 *
 * 设计转变：不再指定"必须调什么工具"，而是给"目标 + 成功标准"。
 * Agent 自主选择工具来达成目标。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubTask {

    /** 子任务 ID */
    private String id;

    /** 简短标签（用于前端展示） */
    private String label;

    /** 子任务描述（给 Agent 的上下文） */
    private String description;

    /** 任务目标（如 "生成古诗词笔记思维导图"） */
    private String goal;

    /** 成功标准——这些条件全部满足即目标达成（如 ["mindmap"]） */
    private java.util.List<String> successCriteria;

    // ========== 以下字段保留但降级为可选参考 ==========

    /** 建议的工具（可选参考，Agent 可自主选择其他工具） */
    private String toolHint;

    /** 已废弃——改用 successCriteria 判断 */
    @Deprecated
    private boolean mustUseTool;

    /** 已废弃 */
    @Deprecated
    private String requiredEvidenceLevel;
}
