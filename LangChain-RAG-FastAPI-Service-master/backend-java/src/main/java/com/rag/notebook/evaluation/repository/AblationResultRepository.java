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

    @Query("SELECT a FROM AblationResult a WHERE a.experimentId = 'BASELINE' ORDER BY a.createdAt DESC")
    List<AblationResult> findLatestBaseline();

    @Query("SELECT a FROM AblationResult a WHERE a.userId = :userId ORDER BY a.experimentId, a.createdAt DESC")
    List<AblationResult> findAllByUserIdGroupedByExperiment(@Param("userId") String userId);

    void deleteByExperimentId(String experimentId);
}
