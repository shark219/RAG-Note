package com.rag.notebook.evaluation.repository;

import com.rag.notebook.evaluation.entity.AblationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AblationRunRepository extends JpaRepository<AblationRun, Long> {

    Optional<AblationRun> findByRunId(String runId);

    List<AblationRun> findByUserIdOrderByCreatedAtDesc(String userId);

    @Query("SELECT r FROM AblationRun r WHERE r.userId = :userId AND r.status = 'RUNNING'")
    List<AblationRun> findRunningByUserId(@Param("userId") String userId);

    @Query("SELECT r FROM AblationRun r WHERE r.userId = :userId ORDER BY r.createdAt DESC")
    List<AblationRun> findLatestByUserId(@Param("userId") String userId);
}
