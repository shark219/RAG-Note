package com.rag.notebook.agent.repo;

import com.rag.notebook.agent.entity.AgentToolMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AgentToolMetricRepository extends JpaRepository<AgentToolMetric, String> {

    List<AgentToolMetric> findByTaskIdOrderByCreatedAtDesc(String taskId);

    List<AgentToolMetric> findByToolNameOrderByCreatedAtDesc(String toolName);

    List<AgentToolMetric> findByUserIdOrderByCreatedAtDesc(String userId);

    // 统计指标查询
    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    long countBySuccessAndCreatedAtBetween(boolean success, LocalDateTime start, LocalDateTime end);

    @Query("SELECT AVG(m.latencyMs) FROM AgentToolMetric m WHERE m.createdAt BETWEEN :start AND :end")
    Double avgLatencyMsByCreatedAtBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
