package com.rag.notebook.knowledge.repository;

import com.rag.notebook.knowledge.entity.ChromaCleanupTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ChromaCleanupTaskRepository extends JpaRepository<ChromaCleanupTask, Long> {

    List<ChromaCleanupTask> findByStatus(String status);

    List<ChromaCleanupTask> findByStatusAndCreatedAtBefore(String status, LocalDateTime before);

    int deleteByStatusAndCreatedAtBefore(String status, LocalDateTime before);
}
