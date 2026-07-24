package com.rag.notebook.evaluation.repository;

import com.rag.notebook.evaluation.entity.EvaluationReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface EvaluationReportRepository extends JpaRepository<EvaluationReport, Long> {

    Optional<EvaluationReport> findTopByTraceIdOrderByCreatedAtDesc(String traceId);

    boolean existsByTraceId(String traceId);

    List<EvaluationReport> findByUserIdOrderByCreatedAtDesc(String userId);

    @Query("SELECT r FROM EvaluationReport r WHERE r.createdAt >= :start AND r.createdAt < :end ORDER BY r.createdAt DESC")
    List<EvaluationReport> findByDateRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    List<EvaluationReport> findByCreatedAtAfter(LocalDateTime start);

    @Query("SELECT r FROM EvaluationReport r WHERE r.totalScore < 60 ORDER BY r.totalScore ASC")
    List<EvaluationReport> findLowScoreReports();

    @Query("SELECT AVG(r.totalScore) FROM EvaluationReport r WHERE r.createdAt >= :start")
    Double avgTotalScoreByDate(@Param("start") LocalDateTime start);

    @Query("SELECT AVG(r.contextPrecision) FROM EvaluationReport r WHERE r.createdAt >= :start")
    Double avgContextPrecisionByDate(@Param("start") LocalDateTime start);

    @Query("SELECT AVG(r.contextRecall) FROM EvaluationReport r WHERE r.createdAt >= :start")
    Double avgContextRecallByDate(@Param("start") LocalDateTime start);

    @Query("SELECT AVG(r.faithfulness) FROM EvaluationReport r WHERE r.createdAt >= :start")
    Double avgFaithfulnessByDate(@Param("start") LocalDateTime start);

    @Query("SELECT AVG(r.answerRelevancy) FROM EvaluationReport r WHERE r.createdAt >= :start")
    Double avgAnswerRelevancyByDate(@Param("start") LocalDateTime start);

    @Query("SELECT COUNT(r) FROM EvaluationReport r WHERE r.totalScore < 60 AND r.createdAt >= :start")
    long countLowScoreByDate(@Param("start") LocalDateTime start);

    @Query("SELECT COUNT(r) FROM EvaluationReport r WHERE r.createdAt >= :start")
    long countByDate(@Param("start") LocalDateTime start);
}
