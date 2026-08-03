package com.rag.notebook.evaluation.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "evaluation_test_cases")
public class TestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "question", columnDefinition = "TEXT", nullable = false)
    private String question;

    @Column(name = "ground_truth", columnDefinition = "TEXT", nullable = false)
    private String groundTruth;

    @Column(name = "doc_id", length = 36)
    private String docId;

    @Column(name = "note_id", length = 36)
    private String noteId;

    @Column(name = "source_type", length = 10)
    private String sourceType;

    @Column(name = "difficulty", length = 20)
    private String difficulty;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
