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

    /** 是否强制要求调用工具（true = 不允许跳过） */
    private boolean mustUseTool;

    /** 所需的最低证据级别（可选，如 "CONTENT_EVIDENCE"）。为空时由 CompletionGate 自动推断 */
    private String requiredEvidenceLevel;

    // ========== 新增：目标追踪字段 ==========

    /** 任务目标描述（如 "增强古诗词笔记并生成思维导图"） */
    private String goal;

    /** 完成目标需要产出的 artifact 列表（如 ["note_updated", "mindmap"]） */
    private java.util.List<String> requiredArtifacts;

    /** 停止条件描述（如 "作者信息已追加、思维导图已生成"） */
    private String stopCondition;
}
