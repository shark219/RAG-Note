package com.rag.notebook.note.repository;

import com.rag.notebook.note.entity.NoteChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NoteChunkRepository extends JpaRepository<NoteChunk, String> {

    List<NoteChunk> findByNoteIdOrderByChunkIndexAsc(String noteId);

    void deleteByNoteId(String noteId);
}
