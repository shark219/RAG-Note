package com.rag.notebook.knowledge.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@NoArgsConstructor
@Entity
@Table(name = "knowledge_document_chunk", indexes = {
        @Index(name = "idx_chunk_document_id", columnList = "document_id"),
        @Index(name = "idx_chunk_doc_index", columnList = "document_id, chunk_index"),
        @Index(name = "idx_chunk_parent", columnList = "document_id, parent_id")
})
public class KnowledgeDocumentChunk {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private KnowledgeDocument document;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "retrieval_text", columnDefinition = "TEXT")
    private String retrievalText;

    @Column(name = "content_type", length = 50)
    private String contentType = "text";

    @Column(name = "section_path", length = 700)
    private String sectionPath;

    @Column(name = "parent_id", length = 80)
    private String parentId;

    @Column(name = "page_start")
    private Integer pageStart;

    @Column(name = "page_end")
    private Integer pageEnd;

    @Column(name = "token_count")
    private Integer tokenCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "JSON")
    private Map<String, Object> metadataJson;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
