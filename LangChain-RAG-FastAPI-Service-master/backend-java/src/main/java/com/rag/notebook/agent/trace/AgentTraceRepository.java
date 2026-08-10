package com.rag.notebook.agent.trace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AgentTraceRepository extends JpaRepository<AgentTrace, String> {

    Optional<AgentTrace> findByTraceId(String traceId);

    List<AgentTrace> findByTaskId(String taskId);

    List<AgentTrace> findByUserId(String userId);

    List<AgentTrace> findByUserIdOrderByCreatedAtDesc(String userId);

    @Query("SELECT t FROM AgentTrace t WHERE t.userId = :userId AND t.createdAt >= :startTime ORDER BY t.createdAt DESC")
    List<AgentTrace> findRecentTracesByUser(@Param("userId") String userId, @Param("startTime") LocalDateTime startTime);

    @Query("SELECT t FROM AgentTrace t WHERE t.status = :status ORDER BY t.createdAt DESC")
    List<AgentTrace> findByStatus(@Param("status") String status);

    @Query("SELECT AVG(t.totalLatencyMs) FROM AgentTrace t WHERE t.userId = :userId AND t.status = 'COMPLETED'")
    Long getAvgLatencyByUser(@Param("userId") String userId);

    @Query("SELECT AVG(t.totalTokens) FROM AgentTrace t WHERE t.userId = :userId AND t.status = 'COMPLETED'")
    Integer getAvgTokensByUser(@Param("userId") String userId);
}
