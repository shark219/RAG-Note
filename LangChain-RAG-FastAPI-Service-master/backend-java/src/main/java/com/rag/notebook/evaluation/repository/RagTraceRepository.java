package com.rag.notebook.evaluation.repository;

import com.rag.notebook.evaluation.entity.RagTrace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RagTraceRepository extends JpaRepository<RagTrace, String> {

    List<RagTrace> findByUserIdOrderByCreatedAtDesc(String userId);

    @Query("SELECT t FROM RagTrace t WHERE t.createdAt >= :start AND t.createdAt < :end ORDER BY t.createdAt DESC")
    List<RagTrace> findByDateRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT t FROM RagTrace t WHERE t.userId = :userId AND t.createdAt >= :start AND t.createdAt < :end ORDER BY t.createdAt DESC")
    List<RagTrace> findByUserIdAndDateRange(@Param("userId") String userId,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);

    @Query("SELECT t FROM RagTrace t WHERE t.userFeedback IS NOT NULL ORDER BY t.createdAt DESC")
    List<RagTrace> findTracesWithFeedback();

    @Query("SELECT t FROM RagTrace t WHERE t.userFeedback IS NOT NULL AND t.userFeedback <= 2 ORDER BY t.createdAt DESC")
    List<RagTrace> findLowScoreTraces();

    @Query("SELECT COUNT(t) FROM RagTrace t WHERE t.createdAt >= :start")
    long countByDate(@Param("start") LocalDateTime start);

    @Query("SELECT AVG(t.totalLatencyMs) FROM RagTrace t WHERE t.createdAt >= :start")
    Double avgLatencyByDate(@Param("start") LocalDateTime start);

    @Query("SELECT AVG(t.userFeedback) FROM RagTrace t WHERE t.userFeedback IS NOT NULL AND t.createdAt >= :start")
    Double avgUserFeedbackByDate(@Param("start") LocalDateTime start);
}
