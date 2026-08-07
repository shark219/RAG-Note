package com.rag.notebook.agent.repo;

import com.rag.notebook.agent.entity.AgentTaskEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentTaskEventRepository extends JpaRepository<AgentTaskEvent, String> {
    List<AgentTaskEvent> findByTaskIdOrderByCreatedAtAsc(String taskId);
}
