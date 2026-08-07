package com.rag.notebook.agent.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.notebook.agent.SubTask;
import com.rag.notebook.agent.entity.AgentTask;
import com.rag.notebook.agent.entity.AgentTaskStep;
import com.rag.notebook.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class AgentTaskResumeService {

    private static final Set<String> RESUMABLE_STATUSES = Set.of(
            AgentTaskStatus.WAITING_USER.name(),
            AgentTaskStatus.BLOCKED.name(),
            AgentTaskStatus.FAILED.name()
    );

    private final AgentTaskService agentTaskService;
    private final ObjectMapper objectMapper;

    public AgentTaskResumeService(AgentTaskService agentTaskService,
                                  ObjectMapper objectMapper) {
        this.agentTaskService = agentTaskService;
        this.objectMapper = objectMapper;
    }

    public AgentResumeContext prepareResume(String taskId, String userId, String userMessage) {
        AgentTask task = agentTaskService.getTaskByUser(taskId, userId);
        if (!RESUMABLE_STATUSES.contains(task.getStatus())) {
            throw new BusinessException(400, "当前任务状态不允许恢复: " + task.getStatus());
        }

        String resumedSummary = buildResumeSummary(task.getCurrentSummary(), userMessage);
        task.setCurrentSummary(resumedSummary);
        task.setFailureReason(null);
        task.setFinishedAt(null);
        agentTaskService.saveTask(task, AgentTaskStatus.RUNNING);

        List<SubTask> remainingSteps = resolveRemainingSteps(task);
        String resumedQuery = buildResumedQuery(task, userMessage);
        String stepId = remainingSteps.isEmpty() ? null : remainingSteps.get(0).getId();

        return new AgentResumeContext(
                task.getTaskId(),
                task.getSessionId(),
                task.getUserId(),
                resumedQuery,
                resumedSummary,
                task.getExecutionMode(),
                stepId,
                remainingSteps
        );
    }

    public void markResumeReady(AgentResumeContext context) {
        agentTaskService.updateStatus(context.taskId(), AgentTaskStatus.RUNNING);
    }

    public boolean isResumable(String status) {
        return status != null && RESUMABLE_STATUSES.contains(status);
    }

    private List<SubTask> resolveRemainingSteps(AgentTask task) {
        if (task.getCurrentPlanSnapshot() == null || task.getCurrentPlanSnapshot().isBlank()) {
            return List.of();
        }
        List<SubTask> plan;
        try {
            plan = objectMapper.readValue(task.getCurrentPlanSnapshot(), new TypeReference<List<SubTask>>() {});
        } catch (Exception e) {
            throw new BusinessException(500, "任务计划解析失败，无法恢复执行");
        }

        List<AgentTaskStep> stepStates = agentTaskService.getTaskSteps(task.getTaskId());
        List<SubTask> remaining = new ArrayList<>();
        for (SubTask subTask : plan) {
            AgentTaskStep matched = stepStates.stream()
                    .filter(step -> step.getStepId().equals(subTask.getId()))
                    .findFirst()
                    .orElse(null);
            if (matched == null) {
                remaining.add(subTask);
                continue;
            }
            if (AgentTaskStatus.COMPLETED.name().equals(matched.getStatus())) {
                continue;
            }
            if (isConflict(matched, subTask)) {
                throw new BusinessException(409, "已完成步骤与新计划冲突: " + matched.getStepId());
            }
            remaining.add(subTask);
        }
        return remaining;
    }

    private boolean isConflict(AgentTaskStep matched, SubTask subTask) {
        if (matched == null || subTask == null) {
            return false;
        }
        String stepGoal = matched.getGoal() == null ? "" : matched.getGoal().trim();
        String planGoal = subTask.getGoal() == null ? "" : subTask.getGoal().trim();
        if (stepGoal.isBlank() || planGoal.isBlank()) {
            return false;
        }
        return !stepGoal.equals(planGoal) && AgentTaskStatus.COMPLETED.name().equals(matched.getStatus());
    }

    private String buildResumedQuery(AgentTask task, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return task.getOriginalQuery();
        }
        return task.getOriginalQuery() + "\n\n[用户补充信息]\n" + userMessage;
    }

    private String buildResumeSummary(String currentSummary, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return currentSummary;
        }
        if (currentSummary == null || currentSummary.isBlank()) {
            return "用户补充信息：" + userMessage;
        }
        return currentSummary + "\n\n[恢复补充]\n用户补充信息：" + userMessage;
    }
}
