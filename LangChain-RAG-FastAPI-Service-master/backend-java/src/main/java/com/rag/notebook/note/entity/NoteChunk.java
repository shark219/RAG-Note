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

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
