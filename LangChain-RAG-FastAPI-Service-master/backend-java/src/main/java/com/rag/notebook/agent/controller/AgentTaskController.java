package com.rag.notebook.agent.controller;

import com.rag.notebook.agent.SubTask;
import com.rag.notebook.agent.dto.AgentTaskDetailResponse;
import com.rag.notebook.agent.dto.AgentTaskResumeRequest;
import com.rag.notebook.agent.dto.AgentTaskResumeResponse;
import com.rag.notebook.agent.dto.AgentTaskSummaryResponse;
import com.rag.notebook.agent.runtime.AgentTaskResumeService;
import com.rag.notebook.agent.runtime.AgentTaskService;
import com.rag.notebook.common.auth.UserId;
import com.rag.notebook.common.result.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/agent/tasks")
public class AgentTaskController {

    private final AgentTaskService agentTaskService;
    private final AgentTaskResumeService agentTaskResumeService;

    public AgentTaskController(AgentTaskService agentTaskService,
                               AgentTaskResumeService agentTaskResumeService) {
        this.agentTaskService = agentTaskService;
        this.agentTaskResumeService = agentTaskResumeService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> listTasks(@UserId String userId) {
        List<AgentTaskSummaryResponse.Item> tasks = agentTaskService.listUserTasks(userId);
        return ApiResponse.success(Map.of(
                "tasks", tasks,
                "total", tasks.size()
        ));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<AgentTaskDetailResponse> getTaskDetail(@UserId String userId,
                                                              @PathVariable String taskId) {
        return ApiResponse.success(agentTaskService.getTaskDetail(taskId, userId));
    }

    @PostMapping("/{taskId}/resume")
    public ApiResponse<AgentTaskResumeResponse> resumeTask(@UserId String userId,
                                                           @PathVariable String taskId,
                                                           @RequestBody(required = false) AgentTaskResumeRequest request) {
        String userMessage = request == null ? null : request.getUserMessage();
        var resumeContext = agentTaskResumeService.prepareResume(taskId, userId, userMessage);
        agentTaskResumeService.markResumeReady(resumeContext);
        agentTaskService.resumeTask(resumeContext, userMessage);

        AgentTaskResumeResponse response = new AgentTaskResumeResponse();
        response.setTaskId(resumeContext.taskId());
        response.setSessionId(resumeContext.sessionId());
        response.setStatus("RUNNING");
        response.setExecutionMode(resumeContext.executionMode());
        response.setResumedQuery(resumeContext.resumedQuery());
        response.setResumedSummary(resumeContext.resumedSummary());
        response.setNextStepId(resumeContext.stepId());
        response.setRemainingStepCount(resumeContext.remainingSteps() == null ? 0 : resumeContext.remainingSteps().size());
        response.setRemainingStepLabels(resumeContext.remainingSteps() == null ? List.of() : resumeContext.remainingSteps().stream().map(SubTask::getLabel).toList());
        return ApiResponse.success(response);
    }
}
