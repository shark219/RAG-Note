package com.rag.notebook.review.repo;

import com.rag.notebook.review.entity.ReviewRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReviewRecordRepository extends JpaRepository<ReviewRecord, String> {

    @Query("SELECT r FROM ReviewRecord r WHERE r.userId = :userId AND r.nextReviewAt <= :now ORDER BY r.nextReviewAt ASC")
    List<ReviewRecord> findDueReviews(@Param("userId") String userId, @Param("now") LocalDateTime now);

    Optional<ReviewRecord> findByNoteIdAndUserId(String noteId, String userId);

    void deleteByNoteId(String noteId);

    /**
     * 使用原生 SQL 删除复习记录，绕过 Hibernate 实体追踪，
     * 避免记录已被其他事务删除时事务被标记为回滚。
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM review_records WHERE note_id = :noteId", nativeQuery = true)
    int deleteByNoteIdNative(@Param("noteId") String noteId);
}
