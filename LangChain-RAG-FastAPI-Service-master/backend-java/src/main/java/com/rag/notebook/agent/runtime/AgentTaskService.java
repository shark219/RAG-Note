package com.rag.notebook.agent.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.notebook.agent.SubTask;
import com.rag.notebook.agent.dto.AgentTaskDetailResponse;
import com.rag.notebook.agent.dto.AgentTaskSummaryResponse;
import com.rag.notebook.agent.entity.AgentTask;
import com.rag.notebook.agent.entity.AgentTaskEvent;
import com.rag.notebook.agent.entity.AgentTaskStep;
import com.rag.notebook.agent.repo.AgentTaskEventRepository;
import com.rag.notebook.agent.repo.AgentTaskRepository;
import com.rag.notebook.agent.repo.AgentTaskStepRepository;
import com.rag.notebook.chat.repo.ChatSessionRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class AgentTaskService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AgentTaskRepository taskRepository;
    private final AgentTaskStepRepository stepRepository;
    private final AgentTaskEventRepository eventRepository;
    private final ChatSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final com.rag.notebook.agent.AgentService agentService;
    private final AgentTaskResumeService agentTaskResumeService;

    public AgentTaskService(AgentTaskRepository taskRepository,
                            AgentTaskStepRepository stepRepository,
                            AgentTaskEventRepository eventRepository,
                            ChatSessionRepository sessionRepository,
                            ObjectMapper objectMapper,
                            @org.springframework.context.annotation.Lazy com.rag.notebook.agent.AgentService agentService,
                            @org.springframework.context.annotation.Lazy AgentTaskResumeService agentTaskResumeService) {
        this.taskRepository = taskRepository;
        this.stepRepository = stepRepository;
        this.eventRepository = eventRepository;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.agentService = agentService;
        this.agentTaskResumeService = agentTaskResumeService;
    }

    @Transactional
    public AgentTask createTask(String userId, String sessionId, String query, String normalizedGoal, String executionMode) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new EntityNotFoundException("会话不存在: " + sessionId);
        }
        AgentTask task = new AgentTask();
        task.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        task.setUserId(userId);
        task.setSessionId(sessionId);
        task.setOriginalQuery(query);
        task.setNormalizedGoal(normalizedGoal);
        task.setExecutionMode(executionMode);
        task.setStatus(AgentTaskStatus.CREATED.name());
        return taskRepository.save(task);
    }

    @Transactional
    public void savePlan(String taskId, List<SubTask> subTasks, String executionMode) {
        AgentTask task = requireTask(taskId);
        task.setExecutionMode(executionMode);
        task.setCurrentPlanSnapshot(writeJson(subTasks));
        task.setStatus(AgentTaskStatus.PLANNING.name());
        taskRepository.save(task);
        stepRepository.deleteAll(stepRepository.findByTaskIdOrderBySequenceNoAsc(taskId));
        for (int i = 0; i < subTasks.size(); i++) {
            SubTask subTask = subTasks.get(i);
            AgentTaskStep step = new AgentTaskStep();
            step.setId(UUID.randomUUID().toString().replace("-", ""));
            step.setTaskId(taskId);
            step.setStepId(subTask.getId() != null ? subTask.getId() : "R-" + (i + 1));
            step.setLabel(subTask.getLabel());
            step.setGoal(subTask.getGoal());
            step.setStatus(AgentTaskStatus.CREATED.name());
            step.setSequenceNo(i + 1);
            step.setDependsOnJson(writeJson(subTask.getDependsOn()));
            step.setSuccessCriteriaJson(writeJson(subTask.getSuccessCriteria()));
            stepRepository.save(step);
        }
    }

    @Transactional(readOnly = true)
    public AgentTask getTask(String taskId) {
        return requireTask(taskId);
    }

    @Transactional(readOnly = true)
    public AgentTask getTaskByUser(String taskId, String userId) {
        return taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new EntityNotFoundException("任务不存在: " + taskId));
    }

    @Transactional(readOnly = true)
    public List<AgentTaskStep> getTaskSteps(String taskId) {
        return stepRepository.findByTaskIdOrderBySequenceNoAsc(taskId);
    }

    @Transactional(readOnly = true)
    public List<AgentTaskSummaryResponse.Item> listUserTasks(String userId) {
        return taskRepository.findByUserIdOrderByStartedAtDesc(userId).stream()
                .map(this::toSummaryItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentTaskDetailResponse getTaskDetail(String taskId, String userId) {
        AgentTask task = taskRepository.findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new EntityNotFoundException("任务不存在: " + taskId));
        AgentTaskDetailResponse response = new AgentTaskDetailResponse();
        response.setTaskId(task.getTaskId());
        response.setSessionId(task.getSessionId());
        response.setStatus(task.getStatus());
        response.setExecutionMode(task.getExecutionMode());
        response.setOriginalQuery(task.getOriginalQuery());
        response.setNormalizedGoal(task.getNormalizedGoal());
        response.setIterationCount(task.getIterationCount());
        response.setToolCallCount(task.getToolCallCount());
        response.setTokenConsumed(task.getTokenConsumed());
        response.setCurrentSummary(task.getCurrentSummary());
        response.setFinalAnswer(task.getFinalAnswer());
        response.setFailureReason(task.getFailureReason());
        response.setResumable(isResumableStatus(task.getStatus()));
        List<AgentTaskStep> steps = stepRepository.findByTaskIdOrderBySequenceNoAsc(taskId);
        response.setSteps(steps.stream().map(this::toStepItem).toList());
        List<String> remainingStepIds = steps.stream()
                .filter(step -> !AgentTaskStatus.COMPLETED.name().equals(step.getStatus()))
                .map(AgentTaskStep::getStepId)
                .toList();
        response.setNextStepId(remainingStepIds.isEmpty() ? null : remainingStepIds.get(0));
        response.setRemainingStepCount(remainingStepIds.size());
        response.setRemainingStepLabels(steps.stream()
                .filter(step -> !AgentTaskStatus.COMPLETED.name().equals(step.getStatus()))
                .map(AgentTaskStep::getLabel)
                .toList());
        response.setEvents(eventRepository.findByTaskIdOrderByCreatedAtAsc(taskId).stream().map(this::toEventItem).toList());
        return response;
    }

    @Transactional
    public AgentTask updateStatus(String taskId, AgentTaskStatus status) {
        AgentTask task = requireTask(taskId);
        task.setStatus(status.name());
        if (status == AgentTaskStatus.COMPLETED || status == AgentTaskStatus.FAILED || status == AgentTaskStatus.CANCELLED) {
            task.setFinishedAt(LocalDateTime.now());
        }
        return taskRepository.save(task);
    }

    @Transactional
    public void updateExecutionSnapshot(String taskId, AgentExecutionSnapshot snapshot) {
        AgentTask task = requireTask(taskId);
        task.setIterationCount(snapshot.iterationCount());
        task.setToolCallCount(snapshot.toolCallCount());
        task.setTokenConsumed(snapshot.tokenConsumed());
        task.setCurrentSummary(snapshot.summary());
        if (snapshot.latestFailureReason() != null && !snapshot.latestFailureReason().isBlank()) {
            task.setFailureReason(snapshot.latestFailureReason());
        }
        taskRepository.save(task);
    }

    @Transactional
    public void saveFinalResult(String taskId, AgentTaskStatus status, String finalAnswer, String failureReason) {
        AgentTask task = requireTask(taskId);
        task.setStatus(status.name());
        task.setFinalAnswer(finalAnswer);
        task.setFailureReason(failureReason);
        task.setFinishedAt(LocalDateTime.now());
        taskRepository.save(task);
    }

    @Transactional
    public void markStepStatus(String taskId, String stepId, AgentTaskStatus status, String resultSummary) {
        List<AgentTaskStep> steps = stepRepository.findByTaskIdOrderBySequenceNoAsc(taskId);
        for (AgentTaskStep step : steps) {
            if (step.getStepId().equals(stepId)) {
                step.setStatus(status.name());
                if (resultSummary != null && !resultSummary.isBlank()) {
                    step.setResultSummary(resultSummary);
                }
                stepRepository.save(step);
                return;
            }
        }
    }

    public Map<String, Object> buildTaskPayload(AgentTask task) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("task_id", task.getTaskId());
        payload.put("session_id", task.getSessionId());
        payload.put("status", task.getStatus());
        payload.put("execution_mode", task.getExecutionMode());
        return payload;
    }

    public SseEmitter resumeTask(AgentResumeContext resumeContext, String userMessage) {
        return agentService.resumeAgentTask(resumeContext, userMessage);
    }

    public SseEmitter resumeTaskStream(String taskId, String userId, String userMessage) {
        var resumeContext = agentTaskResumeService.prepareResume(taskId, userId, userMessage);
        agentTaskResumeService.markResumeReady(resumeContext);
        return agentService.resumeAgentTask(resumeContext, userMessage);
    }

    @Transactional
    public AgentTask saveTask(AgentTask task, AgentTaskStatus status) {
        task.setStatus(status.name());
        return taskRepository.save(task);
    }

    private AgentTaskSummaryResponse.Item toSummaryItem(AgentTask task) {
        AgentTaskSummaryResponse.Item item = new AgentTaskSummaryResponse.Item();
        item.setTaskId(task.getTaskId());
        item.setSessionId(task.getSessionId());
        item.setStatus(task.getStatus());
        item.setExecutionMode(task.getExecutionMode());
        item.setOriginalQuery(task.getOriginalQuery());
        item.setNormalizedGoal(task.getNormalizedGoal());
        item.setIterationCount(task.getIterationCount());
        item.setToolCallCount(task.getToolCallCount());
        item.setCurrentSummary(task.getCurrentSummary());
        item.setResumable(isResumableStatus(task.getStatus()));
        item.setStartedAt(formatTime(task.getStartedAt()));
        item.setFinishedAt(formatTime(task.getFinishedAt()));
        return item;
    }

    private AgentTaskDetailResponse.StepItem toStepItem(AgentTaskStep step) {
        AgentTaskDetailResponse.StepItem item = new AgentTaskDetailResponse.StepItem();
        item.setStepId(step.getStepId());
        item.setLabel(step.getLabel());
        item.setGoal(step.getGoal());
        item.setStatus(step.getStatus());
        item.setSequenceNo(step.getSequenceNo());
        item.setResultSummary(step.getResultSummary());
        return item;
    }

    private AgentTaskDetailResponse.EventItem toEventItem(AgentTaskEvent event) {
        AgentTaskDetailResponse.EventItem item = new AgentTaskDetailResponse.EventItem();
        item.setEventType(event.getEventType());
        item.setPayloadJson(event.getPayloadJson());
        item.setCreatedAt(formatTime(event.getCreatedAt()));
        return item;
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? null : time.format(TIME_FORMATTER);
    }

    private boolean isResumableStatus(String status) {
        return AgentTaskStatus.WAITING_USER.name().equals(status)
                || AgentTaskStatus.BLOCKED.name().equals(status)
                || AgentTaskStatus.FAILED.name().equals(status);
    }

    private AgentTask requireTask(String taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("任务不存在: " + taskId));
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize agent task payload: {}", e.getMessage());
            return null;
        }
    }

    public String findPendingTaskBySession(String sessionId, String userId) {
        List<AgentTask> tasks = taskRepository.findBySessionIdAndUserIdOrderByStartedAtDesc(sessionId, userId);
        for (AgentTask task : tasks) {
            if (AgentTaskStatus.WAITING_USER.name().equals(task.getStatus())) {
                return task.getTaskId();
            }
        }
        return null;
    }
}
