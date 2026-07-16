package com.rag.notebook.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Supervisor 拆分的子任务
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubTask {

    /** 子任务 ID，如 "R-1", "R-2" */
    private String id;

    /** 简短标签（用于前端展示） */
    private String label;

    /** 子任务详细描述（给 Researcher 的指令） */
    private String description;

    /** 建议使用的工具（可选，如 "ragSummary", "searchNotes"） */
    private String toolHint;
}
