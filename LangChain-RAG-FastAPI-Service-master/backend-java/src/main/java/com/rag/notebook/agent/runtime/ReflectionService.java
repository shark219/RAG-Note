package com.rag.notebook.agent.runtime;

import com.rag.notebook.agent.AgentState;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ReflectionService {

    public ReflectionResult reflect(WorkerLoopContext context, String latestToolName, String latestRawResult) {
        AgentState state = context.agentState();
        if (state == null) {
            return ReflectionResult.noOp();
        }

        boolean shouldReflect = state.getConsecutiveNoProgress() >= 2 || hasRepeatedToolFailure(state);
        if (!shouldReflect) {
            return ReflectionResult.noOp();
        }

        List<String> missingEvidence = new ArrayList<>();
        if (!state.hasGoodQualityResult()) {
            missingEvidence.add("缺少足够的高质量证据来支撑最终回答");
        }
        if (latestToolName != null && latestRawResult != null && latestRawResult.contains("找不到")) {
            missingEvidence.add("当前检索路径没有命中目标内容");
        }

        List<String> recommendedActions = new ArrayList<>();
        recommendedActions.add("切换检索词或改用其他工具路径");
        if (context.successCriteria() != null && !context.successCriteria().isEmpty()) {
            recommendedActions.add("对照 successCriteria 补齐缺失证据");
        }

        String failureType = state.getConsecutiveNoProgress() >= 2 ? "NO_PROGRESS" : "REPEATED_TOOL_FAILURE";
        String rootCause = missingEvidence.isEmpty() ? "当前执行路径未形成有效推进" : String.join("；", missingEvidence);
        String summary = "任务在当前路径上推进不足，需要切换策略";
        boolean shouldReplan = state.getConsecutiveNoProgress() >= 2;
        return new ReflectionResult(false, shouldReplan, false, failureType, rootCause, missingEvidence, recommendedActions, summary, 0.42d);
    }

    private boolean hasRepeatedToolFailure(AgentState state) {
        if (state.getToolHistory().size() < 2) {
            return false;
        }
        AgentState.ToolCallRecord last = state.getToolHistory().get(state.getToolHistory().size() - 1);
        AgentState.ToolCallRecord previous = state.getToolHistory().get(state.getToolHistory().size() - 2);
        return last.quality() == AgentState.ResultQuality.ERROR
                && previous.quality() == AgentState.ResultQuality.ERROR
                && last.toolName().equals(previous.toolName());
    }
}
