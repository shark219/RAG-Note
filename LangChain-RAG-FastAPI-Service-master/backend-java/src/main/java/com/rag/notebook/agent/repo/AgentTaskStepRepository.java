package com.rag.notebook.agent.repo;

import com.rag.notebook.agent.entity.AgentTaskStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentTaskStepRepository extends JpaRepository<AgentTaskStep, String> {
    List<AgentTaskStep> findByTaskIdOrderBySequenceNoAsc(String taskId);
}
