package com.rag.notebook.knowledge.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "chroma_cleanup_task", indexes = {
        @Index(name = "idx_cleanup_status", columnList = "status")
})
public class ChromaCleanupTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_id", length = 36, nullable = false)
    private String docId;

    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(name = "collection_name", length = 100, nullable = false)
    private String collectionName;

    @Column(name = "task_type", length = 20)
    private String taskType = "knowledge"; // knowledge / note

    @Column(name = "retry_count")
    private int retryCount = 0;

    @Column(name = "max_retry")
    private int maxRetry = 10;

    @Column(name = "status", length = 20)
    private String status = "pending"; // pending / failed

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
