package com.rag.notebook.review.repo;

import com.rag.notebook.review.entity.ReviewRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReviewRecordRepository extends JpaRepository<ReviewRecord, String> {

    @Query("SELECT r FROM ReviewRecord r WHERE r.userId = :userId AND r.nextReviewAt <= :now ORDER BY r.nextReviewAt ASC")
    List<ReviewRecord> findDueReviews(@Param("userId") String userId, @Param("now") LocalDateTime now);

    Optional<ReviewRecord> findByNoteIdAndUserId(String noteId, String userId);

    void deleteByNoteId(String noteId);
}
