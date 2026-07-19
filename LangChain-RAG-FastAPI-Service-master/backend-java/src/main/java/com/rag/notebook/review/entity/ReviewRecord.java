package com.rag.notebook.review.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "review_records")
public class ReviewRecord {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "note_id", length = 36, nullable = false)
    private String noteId;

    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    @Column(name = "review_count")
    private Integer reviewCount = 0;

    @Column(name = "next_review_at")
    private LocalDateTime nextReviewAt;

    @Column(name = "interval_days")
    private Integer intervalDays = 1;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
