package com.rag.notebook.evaluation.repository;

import com.rag.notebook.evaluation.entity.AblationEvaluationReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AblationEvaluationReportRepository extends JpaRepository<AblationEvaluationReport, Long> {

    List<AblationEvaluationReport> findByRunId(String runId);

    List<AblationEvaluationReport> findByRunIdAndExperimentId(String runId, String experimentId);

    @Query("SELECT r FROM AblationEvaluationReport r WHERE r.runId = :runId AND r.experimentId = :experimentId")
    List<AblationEvaluationReport> findByRunAndExperiment(@Param("runId") String runId, @Param("experimentId") String experimentId);

    @Query("SELECT AVG(r.totalScore) FROM AblationEvaluationReport r WHERE r.runId = :runId")
    Double avgTotalScoreByRunId(@Param("runId") String runId);

    void deleteByRunId(String runId);
}
