package com.rag.notebook.agent.repo;

import com.rag.notebook.agent.entity.AgentToolMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentToolMetricRepository extends JpaRepository<AgentToolMetric, String> {

    List<AgentToolMetric> findByTaskIdOrderByCreatedAtDesc(String taskId);

    List<AgentToolMetric> findByToolNameOrderByCreatedAtDesc(String toolName);

    List<AgentToolMetric> findByUserIdOrderByCreatedAtDesc(String userId);
}
