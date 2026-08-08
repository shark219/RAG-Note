package com.rag.notebook.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Supervisor 拆分的子任务。
 *
 * 执行模式：
 *   SEQUENTIAL — 子任务间有依赖，必须交给同一个 Agent 按顺序执行
 *   PARALLEL   — 子任务相互独立，可交给多个 Agent 并行执行
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
    private List<String> successCriteria;

    // ========== 依赖与执行模式 ==========

    /** 依赖的子任务 ID 列表。空列表或无依赖表示可以独立执行 */
    private List<String> dependsOn = new ArrayList<>();

    /** 执行模式：SEQUENTIAL（顺序）/ PARALLEL（并行）。默认 PARALLEL */
    private String executionMode = "PARALLEL";

    // ========== 以下字段保留但降级为可选参考 ==========

    /** 建议的工具（可选参考，Agent 可自主选择其他工具） */
    private String toolHint;
}
