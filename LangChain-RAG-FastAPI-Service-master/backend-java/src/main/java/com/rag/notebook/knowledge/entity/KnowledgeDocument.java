package com.rag.notebook.knowledge.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Entity
@Table(name = "knowledge_document", indexes = {
        @Index(name = "idx_knowledge_doc_user_id", columnList = "user_id"),
        @Index(name = "idx_knowledge_doc_md5", columnList = "md5")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_md5", columnNames = {"user_id", "md5"})
})
public class KnowledgeDocument {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(name = "filename", length = 500, nullable = false)
    private String filename;

    @Column(name = "original_filename", length = 500)
    private String originalFilename;

    @Column(name = "md5", length = 32, nullable = false)
    private String md5;

    @Column(name = "chunk_count")
    private int chunkCount = 0;

    @Column(name = "file_size")
    private Long fileSize = 0L;

    @Column(name = "status", length = 20)
    private String status = "completed"; // processing/completed/failed

    @Column(name = "preview", columnDefinition = "TEXT")
    private String preview;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("chunkIndex ASC")
    private List<KnowledgeDocumentChunk> chunks = new ArrayList<>();
}
