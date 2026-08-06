package com.rag.notebook.rag;

import com.rag.notebook.knowledge.entity.KnowledgeDocument;
import com.rag.notebook.knowledge.entity.KnowledgeDocumentChunk;
import com.rag.notebook.knowledge.repository.KnowledgeDocumentChunkRepository;
import com.rag.notebook.knowledge.repository.KnowledgeDocumentRepository;
import com.rag.notebook.note.entity.Note;
import com.rag.notebook.note.entity.NoteChunk;
import com.rag.notebook.note.repo.NoteRepository;
import com.rag.notebook.note.repository.NoteChunkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KeywordSearchService {

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeDocumentChunkRepository knowledgeChunkRepository;
    private final NoteRepository noteRepository;
    private final NoteChunkRepository noteChunkRepository;

    public KeywordSearchService(KnowledgeDocumentRepository documentRepository,
                                KnowledgeDocumentChunkRepository knowledgeChunkRepository,
                                NoteRepository noteRepository,
                                NoteChunkRepository noteChunkRepository) {
        this.documentRepository = documentRepository;
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.noteRepository = noteRepository;
        this.noteChunkRepository = noteChunkRepository;
    }

    public List<Map<String, Object>> searchKnowledge(String userId, String query, int topK,
                                                     Set<String> identifiers) {
        Set<String> selected = identifiers == null ? Set.of() : identifiers;
        List<Map<String, Object>> results = new ArrayList<>();
        for (KnowledgeDocument document : documentRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (!selected.isEmpty() && !matchesIdentifier(document, selected)) continue;
            for (KnowledgeDocumentChunk chunk : knowledgeChunkRepository.findByDocumentIdOrderByChunkIndexAsc(document.getId())) {
                String text = value(chunk.getRetrievalText(), chunk.getContent());
                int score = score(query, text, document.getOriginalFilename(), document.getFilename());
                if (score == 0) continue;
                Map<String, Object> result = new HashMap<>();
                result.put("chunk_id", document.getMd5() + "_" + chunk.getChunkIndex());
                result.put("user_id", userId);
                result.put("filename", document.getFilename());
                result.put("original_filename", document.getOriginalFilename());
                result.put("md5", document.getMd5());
                result.put("doc_id", document.getId());
                result.put("index", chunk.getChunkIndex());
                result.put("content", chunk.getContent());
                result.put("retrieval_text", text);
                result.put("content_type", chunk.getContentType());
                result.put("section_path", chunk.getSectionPath());
                result.put("page_start", chunk.getPageStart());
                result.put("page_end", chunk.getPageEnd());
                result.put("keyword_score", score);
                result.put("similarity", score / 100.0);
                result.put("retrieval_mode", "keyword");
                result.put("source", "knowledge_base");
                result.put("source_type", "knowledge_base");
                results.add(result);
            }
        }
        return results.stream()
                .sorted(Comparator.comparingInt((Map<String, Object> r) -> (Integer) r.get("keyword_score")).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> searchNotes(String userId, String query, int topK) {
        Map<String, Note> notes = noteRepository.findAllByUserId(userId).stream()
                .collect(Collectors.toMap(Note::getId, n -> n, (a, b) -> a));
        List<Map<String, Object>> results = new ArrayList<>();
        for (NoteChunk chunk : noteChunkRepository.findByNoteIdInOrderByNoteIdAscChunkIndexAsc(new ArrayList<>(notes.keySet()))) {
            Note note = notes.get(chunk.getNoteId());
            if (note == null) continue;
            String text = value(chunk.getRetrievalText(), chunk.getContent());
            int score = score(query, text, note.getTitle(), chunk.getSectionPath());
            if (score == 0) continue;
            Map<String, Object> result = new HashMap<>();
            result.put("chunk_id", chunk.getNoteId() + "_" + chunk.getChunkIndex());
            result.put("note_id", chunk.getNoteId());
            result.put("title", note.getTitle());
            result.put("content", chunk.getContent());
            result.put("retrieval_text", text);
            result.put("index", chunk.getChunkIndex());
            result.put("section_path", chunk.getSectionPath());
            result.put("keyword_score", score);
            result.put("similarity", score / 100.0);
            result.put("retrieval_mode", "keyword");
            result.put("source", "note");
            result.put("source_type", "note");
            results.add(result);
        }
        return results.stream()
                .sorted(Comparator.comparingInt((Map<String, Object> r) -> (Integer) r.get("keyword_score")).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    private int score(String query, String content, String... metadata) {
        List<String> terms = terms(query);
        if (terms.isEmpty()) return 0;
        String body = value(content, "").toLowerCase(Locale.ROOT);
        String meta = Arrays.stream(metadata).filter(Objects::nonNull)
                .collect(Collectors.joining(" ")).toLowerCase(Locale.ROOT);
        int score = 0;
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (!normalizedQuery.isBlank() && body.contains(normalizedQuery)) score += 40;
        if (!normalizedQuery.isBlank() && meta.contains(normalizedQuery)) score += 50;
        for (String term : terms) {
            if (meta.contains(term)) score += 10;
            if (body.contains(term)) score += 2 * count(body, term);
        }
        return score;
    }

    private List<String> terms(String query) {
        if (query == null || query.isBlank()) return List.of();
        String normalized = query.toLowerCase(Locale.ROOT).trim();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String part : normalized.split("\\s+")) {
            if (part.length() > 1) result.add(part);
            if (containsCjk(part)) {
                for (int i = 0; i < part.length(); i++) result.add(part.substring(i, i + 1));
            }
        }
        return result.stream().filter(s -> s.length() > 0).toList();
    }

    private boolean containsCjk(String text) {
        return text.codePoints().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF);
    }

    private int count(String text, String term) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(term, index)) >= 0) {
            count++;
            index += term.length();
        }
        return count;
    }

    private boolean matchesIdentifier(KnowledgeDocument document, Set<String> identifiers) {
        return identifiers.contains(document.getId()) || identifiers.contains(document.getMd5())
                || identifiers.contains(document.getFilename()) || identifiers.contains(document.getOriginalFilename());
    }

    private String value(String first, String fallback) {
        return first == null || first.isBlank() ? (fallback == null ? "" : fallback) : first;
    }
}
