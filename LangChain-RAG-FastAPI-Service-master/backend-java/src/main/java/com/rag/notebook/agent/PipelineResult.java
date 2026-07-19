package com.rag.notebook.agent;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 多 Agent 流水线执行结果
 */
@Data
public class PipelineResult {

    /** Supervisor 拆分的子任务列表 */
    private List<SubTask> subTasks;

    /** 各子任务的执行结果：subTaskId → result */
    private Map<String, String> results;

    /** Writer 合成的最终回答 */
    private String finalAnswer;

    /** 是否使用了多 Agent 流水线（false 表示走了单 Agent） */
    private boolean usedPipeline;
}
