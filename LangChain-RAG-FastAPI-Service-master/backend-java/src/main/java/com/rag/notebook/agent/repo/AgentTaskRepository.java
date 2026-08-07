package com.rag.notebook.agent.repo;

import com.rag.notebook.agent.entity.AgentTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentTaskRepository extends JpaRepository<AgentTask, String> {
    List<AgentTask> findBySessionIdOrderByStartedAtDesc(String sessionId);
    List<AgentTask> findByUserIdOrderByStartedAtDesc(String userId);
    Optional<AgentTask> findByTaskIdAndUserId(String taskId, String userId);
}
