package com.rag.notebook.agent.repo;

import com.rag.notebook.agent.entity.AgentReflection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AgentReflectionRepository extends JpaRepository<AgentReflection, String> {

    List<AgentReflection> findByTaskIdOrderByCreatedAtDesc(String taskId);

    List<AgentReflection> findByTaskIdAndStepId(String taskId, String stepId);

    // 统计指标查询
    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    long countByShouldReplanAndCreatedAtBetween(boolean shouldReplan, LocalDateTime start, LocalDateTime end);

    long countByFailureTypeAndCreatedAtBetween(String failureType, LocalDateTime start, LocalDateTime end);
}
