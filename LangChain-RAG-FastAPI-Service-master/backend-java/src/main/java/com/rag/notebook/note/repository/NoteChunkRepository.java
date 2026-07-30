package com.rag.notebook.note.repository;

import com.rag.notebook.note.entity.NoteChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NoteChunkRepository extends JpaRepository<NoteChunk, String> {

    List<NoteChunk> findByNoteIdOrderByChunkIndexAsc(String noteId);

    @Query("SELECT c FROM NoteChunk c WHERE c.noteId = :noteId AND c.chunkIndex BETWEEN :startIndex AND :endIndex ORDER BY c.chunkIndex ASC")
    List<NoteChunk> findNeighborhood(@Param("noteId") String noteId,
                                     @Param("startIndex") int startIndex,
                                     @Param("endIndex") int endIndex);

    @Query("SELECT c FROM NoteChunk c WHERE c.noteId = :noteId AND c.sectionPath = :sectionPath ORDER BY c.chunkIndex ASC")
    List<NoteChunk> findByNoteIdAndSectionPath(@Param("noteId") String noteId,
                                               @Param("sectionPath") String sectionPath);

    void deleteByNoteId(String noteId);
}
