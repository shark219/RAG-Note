package com.rag.notebook.rag;

import com.rag.notebook.knowledge.entity.KnowledgeDocument;
import com.rag.notebook.knowledge.entity.KnowledgeDocumentChunk;
import com.rag.notebook.knowledge.repository.KnowledgeDocumentChunkRepository;
import com.rag.notebook.knowledge.repository.KnowledgeDocumentRepository;
import com.rag.notebook.note.entity.Note;
import com.rag.notebook.note.entity.NoteChunk;
import com.rag.notebook.note.repo.NoteRepository;
import com.rag.notebook.note.repository.NoteChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KeywordSearchServiceTest {

    @Mock private KnowledgeDocumentRepository documentRepository;
    @Mock private KnowledgeDocumentChunkRepository knowledgeChunkRepository;
    @Mock private NoteRepository noteRepository;
    @Mock private NoteChunkRepository noteChunkRepository;

    private KeywordSearchService service;

    private KnowledgeDocument buildDoc(String id, String filename, String md5) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(id);
        doc.setUserId("user-1");
        doc.setFilename(filename);
        doc.setOriginalFilename(filename);
        doc.setMd5(md5);
        return doc;
    }

    private KnowledgeDocumentChunk buildChunk(String id, int index, String content) {
        KnowledgeDocumentChunk c = new KnowledgeDocumentChunk();
        c.setId(id);
        c.setChunkIndex(index);
        c.setContent(content);
        c.setRetrievalText(content);
        return c;
    }

    private Note buildNote(String id, String title) {
        Note note = new Note();
        note.setId(id);
        note.setUserId("user-1");
        note.setTitle(title);
        note.setContent("content of " + title);
        return note;
    }

    private NoteChunk buildNoteChunk(String id, String noteId, int index, String content) {
        NoteChunk c = new NoteChunk();
        c.setId(id);
        c.setNoteId(noteId);
        c.setChunkIndex(index);
        c.setContent(content);
        c.setRetrievalText(content);
        return c;
    }

    @BeforeEach
    void setUp() {
        service = new KeywordSearchService(documentRepository, knowledgeChunkRepository,
                noteRepository, noteChunkRepository);
    }

    @Test
    void searchKnowledgeReturnsEmptyWhenNoDocuments() {
        when(documentRepository.findByUserIdOrderByCreatedAtDesc("user-1")).thenReturn(List.of());
        List<Map<String, Object>> results = service.searchKnowledge("user-1", "Spring事务", 5, Set.of());
        assertTrue(results.isEmpty());
    }

    @Test
    void searchKnowledgeReturnsScoredResultsOnKeywordMatch() {
        KnowledgeDocument doc = buildDoc("doc-1", "spring-guide.md", "md5-1");
        KnowledgeDocumentChunk chunk = buildChunk("chunk-1", 0, "Spring事务传播机制包括 REQUIRED 和 REQUIRES_NEW");
        when(documentRepository.findByUserIdOrderByCreatedAtDesc("user-1")).thenReturn(List.of(doc));
        when(knowledgeChunkRepository.findByDocumentIdOrderByChunkIndexAsc("doc-1")).thenReturn(List.of(chunk));

        List<Map<String, Object>> results = service.searchKnowledge("user-1", "Spring事务", 5, Set.of());
        assertFalse(results.isEmpty());
        Map<String, Object> first = results.get(0);
        assertTrue(((Integer) first.get("keyword_score")) > 0);
        assertEquals("keyword", first.get("retrieval_mode"));
    }

    @Test
    void searchNotesReturnsEmptyWhenNoNotes() {
        when(noteRepository.findAllByUserId("user-1")).thenReturn(List.of());
        when(noteChunkRepository.findByNoteIdInOrderByNoteIdAscChunkIndexAsc(List.of())).thenReturn(List.of());
        List<Map<String, Object>> results = service.searchNotes("user-1", "Spring事务", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void searchNotesReturnsScoredResultsOnTitleMatch() {
        Note note = buildNote("note-1", "Spring事务传播机制详解");
        NoteChunk chunk = buildNoteChunk("nc-1", "note-1", 0, "REQUIRED 和 REQUIRES_NEW 的区别");
        when(noteRepository.findAllByUserId("user-1")).thenReturn(List.of(note));
        when(noteChunkRepository.findByNoteIdInOrderByNoteIdAscChunkIndexAsc(List.of("note-1")))
                .thenReturn(List.of(chunk));

        List<Map<String, Object>> results = service.searchNotes("user-1", "Spring事务", 5);
        assertFalse(results.isEmpty());
        Map<String, Object> first = results.get(0);
        assertEquals("keyword", first.get("retrieval_mode"));
        assertTrue(((Integer) first.get("keyword_score")) > 0);
    }

    @Test
    void searchKnowledgeFiltersByIdentifier() {
        KnowledgeDocument doc1 = buildDoc("doc-1", "a.md", "md5-1");
        KnowledgeDocument doc2 = buildDoc("doc-2", "b.md", "md5-2");
        KnowledgeDocumentChunk c1 = buildChunk("c1", 0, "Spring事务传播");
        when(documentRepository.findByUserIdOrderByCreatedAtDesc("user-1")).thenReturn(List.of(doc1, doc2));
        when(knowledgeChunkRepository.findByDocumentIdOrderByChunkIndexAsc("doc-1")).thenReturn(List.of(c1));

        List<Map<String, Object>> results = service.searchKnowledge("user-1", "Spring事务", 5, Set.of("doc-1"));
        assertEquals(1, results.size());
        assertEquals("doc-1", results.get(0).get("doc_id"));
    }
}
