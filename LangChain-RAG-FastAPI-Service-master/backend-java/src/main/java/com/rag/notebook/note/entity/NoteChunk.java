package com.rag.notebook.note.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "note_chunk", indexes = {
        @Index(name = "idx_note_chunk_note_id", columnList = "note_id"),
        @Index(name = "idx_note_chunk_index", columnList = "note_id, chunk_index")
})
public class NoteChunk {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "note_id", length = 36, nullable = false)
    private String noteId;

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

    @Column(name = "previous_chunk_id", length = 80)
    private String previousChunkId;

    @Column(name = "next_chunk_id", length = 80)
    private String nextChunkId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
