package com.rag.notebook.rag;

import com.rag.notebook.config.ApplicationProperties;
import com.rag.notebook.note.entity.Note;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class VectorStoreService {

    private final ApplicationProperties props;
    // In-memory store for note vectors (simplified - in production use ChromaDB HTTP client)
    private final Map<String, Map<String, Object>> noteVectors = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> knowledgeVectors = new ConcurrentHashMap<>();

    public VectorStoreService(ApplicationProperties props) {
        this.props = props;
    }

    public void addNoteVector(Note note) {
        Map<String, Object> vector = new HashMap<>();
        vector.put("note_id", note.getId());
        vector.put("user_id", note.getUserId());
        vector.put("doc_type", "note");
        vector.put("title", note.getTitle());
        vector.put("content", note.getContent());
        vector.put("metadata", Map.of(
                "user_id", note.getUserId(),
                "note_id", note.getId(),
                "doc_type", "note",
                "title", note.getTitle()
        ));
        noteVectors.put(note.getId(), vector);
        log.debug("Added note vector: {}", note.getId());
    }

    public void deleteNoteVector(String noteId) {
        noteVectors.remove(noteId);
        log.debug("Deleted note vector: {}", noteId);
    }

    public List<Map<String, Object>> searchNotes(String userId, String query, int topK) {
        // Simple keyword-based search as placeholder for vector search
        String queryLower = query.toLowerCase();
        return noteVectors.values().stream()
                .filter(v -> userId.equals(v.get("user_id")))
                .filter(v -> {
                    String title = String.valueOf(v.getOrDefault("title", "")).toLowerCase();
                    String content = String.valueOf(v.getOrDefault("content", "")).toLowerCase();
                    return title.contains(queryLower) || content.contains(queryLower);
                })
                .limit(topK)
                .map(v -> {
                    Map<String, Object> result = new HashMap<>(v);
                    result.put("distance", 0.5f);
                    return result;
                })
                .toList();
    }

    public void addKnowledgeDocument(String userId, String filename, String md5,
                                     List<String> chunks, Map<String, Object> metadata) {
        for (int i = 0; i < chunks.size(); i++) {
            String key = md5 + "_" + i;
            Map<String, Object> vector = new HashMap<>();
            vector.put("chunk_id", key);
            vector.put("user_id", userId);
            vector.put("filename", filename);
            vector.put("md5", md5);
            vector.put("content", chunks.get(i));
            vector.put("index", i);
            vector.put("metadata", metadata);
            knowledgeVectors.put(key, vector);
        }
        log.debug("Added {} chunks for document: {}", chunks.size(), filename);
    }

    public void deleteUserKnowledge(String userId) {
        knowledgeVectors.entrySet().removeIf(e -> userId.equals(e.getValue().get("user_id")));
        log.debug("Deleted all knowledge for user: {}", userId);
    }

    public void deleteKnowledgeByFilename(String userId, String filename) {
        knowledgeVectors.entrySet().removeIf(e ->
                userId.equals(e.getValue().get("user_id")) && filename.equals(e.getValue().get("filename")));
    }

    public List<Map<String, Object>> searchKnowledge(String userId, String query, int topK) {
        String queryLower = query.toLowerCase();
        return knowledgeVectors.values().stream()
                .filter(v -> userId.equals(v.get("user_id")))
                .filter(v -> String.valueOf(v.getOrDefault("content", "")).toLowerCase().contains(queryLower))
                .limit(topK)
                .map(v -> {
                    Map<String, Object> result = new HashMap<>(v);
                    result.put("distance", 0.5f);
                    return result;
                })
                .toList();
    }

    public List<Map<String, Object>> getUserDocuments(String userId) {
        Map<String, Map<String, Object>> docGroups = new LinkedHashMap<>();
        knowledgeVectors.values().stream()
                .filter(v -> userId.equals(v.get("user_id")))
                .forEach(v -> {
                    String filename = (String) v.get("filename");
                    docGroups.computeIfAbsent(filename, k -> {
                        Map<String, Object> doc = new HashMap<>();
                        doc.put("filename", filename);
                        doc.put("user_id", userId);
                        doc.put("md5", v.get("md5"));
                        doc.put("chunk_count", 0);
                        doc.put("preview", "");
                        doc.put("chunks", new ArrayList<String>());
                        return doc;
                    });
                    Map<String, Object> doc = docGroups.get(filename);
                    doc.put("chunk_count", ((int) doc.get("chunk_count")) + 1);
                    List<String> contentList = (List<String>) doc.get("chunks");
                    contentList.add((String) v.get("content"));
                });

        return docGroups.values().stream().map(doc -> {
            List<String> chunks = (List<String>) doc.get("chunks");
            String preview = chunks.isEmpty() ? "" : chunks.get(0).substring(0, Math.min(200, chunks.get(0).length()));
            doc.put("preview", preview);
            doc.remove("chunks");
            return doc;
        }).toList();
    }

    public Map<String, Object> getDocumentDetail(String userId, String filename) {
        List<Map<String, Object>> chunks = knowledgeVectors.values().stream()
                .filter(v -> userId.equals(v.get("user_id")) && filename.equals(v.get("filename")))
                .sorted(Comparator.comparingInt(v -> (int) v.getOrDefault("index", 0)))
                .toList();

        if (chunks.isEmpty()) return null;

        StringBuilder content = new StringBuilder();
        List<Map<String, Object>> chunkDetails = new ArrayList<>();
        for (Map<String, Object> chunk : chunks) {
            content.append(chunk.get("content"));
            chunkDetails.add(Map.of(
                    "chunk_id", chunk.get("chunk_id"),
                    "index", chunk.get("index"),
                    "content", chunk.get("content")
            ));
        }

        Map<String, Object> first = chunks.get(0);
        return Map.of(
                "id", first.get("md5"),
                "filename", filename,
                "user_id", userId,
                "chunk_count", chunks.size(),
                "content", content.toString(),
                "chunks", chunkDetails,
                "images", List.of()
        );
    }

    public Map<String, Object> getDocumentChunks(String userId, String filename) {
        List<Map<String, Object>> chunks = knowledgeVectors.values().stream()
                .filter(v -> userId.equals(v.get("user_id")) && filename.equals(v.get("filename")))
                .sorted(Comparator.comparingInt(v -> (int) v.getOrDefault("index", 0)))
                .map(v -> Map.<String, Object>of(
                        "chunk_id", v.get("chunk_id"),
                        "index", v.get("index"),
                        "content", v.get("content"),
                        "metadata", v.getOrDefault("metadata", Map.of()),
                        "images", List.of()
                ))
                .toList();

        return Map.of(
                "filename", filename,
                "total_chunks", chunks.size(),
                "chunks", chunks
        );
    }
}
