package com.rag.notebook.agent.repo;

import com.rag.notebook.agent.entity.AgentTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentTaskRepository extends JpaRepository<AgentTask, String> {
    List<AgentTask> findBySessionIdOrderByStartedAtDesc(String sessionId);
    List<AgentTask> findByUserIdOrderByStartedAtDesc(String userId);
    Optional<AgentTask> findByTaskIdAndUserId(String taskId, String userId);
    List<AgentTask> findBySessionIdAndUserIdOrderByStartedAtDesc(String sessionId, String userId);

    // 统计指标查询
    long countByStartedAtBetween(LocalDateTime start, LocalDateTime end);

    long countByStatusAndStartedAtBetween(String status, LocalDateTime start, LocalDateTime end);

    @Query("SELECT AVG(t.iterationCount) FROM AgentTask t WHERE t.startedAt BETWEEN :start AND :end")
    Double avgIterationCountByStartedAtBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT AVG(t.toolCallCount) FROM AgentTask t WHERE t.startedAt BETWEEN :start AND :end")
    Double avgToolCallCountByStartedAtBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT AVG(t.tokenConsumed) FROM AgentTask t WHERE t.startedAt BETWEEN :start AND :end")
    Double avgTokenConsumedByStartedAtBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
