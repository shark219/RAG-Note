package com.rag.notebook.agent.runtime;

import com.rag.notebook.agent.SubTask;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ReplanningService {

    public ReplanResult replan(WorkerLoopContext context, ReflectionResult reflectionResult) {
        if (reflectionResult == null || !reflectionResult.shouldReplan()) {
            return ReplanResult.noOp();
        }

        List<SubTask> newSteps = new ArrayList<>();
        SubTask step = new SubTask();
        step.setId(context.stepId() != null ? context.stepId() : "R-REPLAN");
        step.setLabel("调整执行策略");
        step.setGoal(context.goal() != null ? context.goal() : context.userQuery());
        step.setDescription(reflectionResult.summary());
        step.setSuccessCriteria(context.successCriteria());
        step.setExecutionMode("SEQUENTIAL");
        newSteps.add(step);
        return new ReplanResult(reflectionResult.rootCause(), newSteps, true, "已根据反思结果生成新的执行建议");
    }
}
