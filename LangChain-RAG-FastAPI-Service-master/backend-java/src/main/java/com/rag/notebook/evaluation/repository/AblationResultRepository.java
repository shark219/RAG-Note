package com.rag.notebook.evaluation.repository;

import com.rag.notebook.evaluation.entity.AblationResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AblationResultRepository extends JpaRepository<AblationResult, Long> {

    List<AblationResult> findByExperimentIdOrderByCreatedAtDesc(String experimentId);

    List<AblationResult> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<AblationResult> findTopByExperimentIdOrderByCreatedAtDesc(String experimentId);

    List<AblationResult> findByRunId(String runId);

    List<AblationResult> findByRunIdAndExperimentId(String runId, String experimentId);

    @Query("SELECT a FROM AblationResult a WHERE a.experimentId = 'BASELINE' ORDER BY a.createdAt DESC")
    List<AblationResult> findLatestBaseline();

    @Query("SELECT a FROM AblationResult a WHERE a.userId = :userId ORDER BY a.experimentId, a.createdAt DESC")
    List<AblationResult> findAllByUserIdGroupedByExperiment(@Param("userId") String userId);

    @Query("SELECT a FROM AblationResult a WHERE a.userId = :userId AND a.runId = :runId ORDER BY a.experimentId, a.createdAt DESC")
    List<AblationResult> findByUserIdAndRunIdGroupedByExperiment(@Param("userId") String userId, @Param("runId") String runId);

    /** 按实验编号前缀查询（用于 R-6 TopK / R-7 RrfK 等参数实验），按创建时间倒序 */
    List<AblationResult> findByUserIdAndExperimentIdStartingWithOrderByCreatedAtDesc(String userId, String experimentIdPrefix);

    void deleteByExperimentId(String experimentId);

    void deleteByRunId(String runId);
}
