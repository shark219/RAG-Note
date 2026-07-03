package com.rag.notebook.rag;

import com.rag.notebook.agent.ModelFactory;
import com.rag.notebook.config.ApplicationProperties;
import com.rag.notebook.note.entity.Note;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VectorStoreService {

    private final ApplicationProperties props;
    private final EmbeddingModel embeddingModel;
    private final Bm25Service bm25Service;

    // ChromaDB 向量存储（连接成功时使用）
    private EmbeddingStore<TextSegment> noteStore;
    private EmbeddingStore<TextSegment> knowledgeStore;
    private boolean chromaAvailable = false;

    // 元数据缓存
    private final Map<String, Map<String, Object>> noteMetadata = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> knowledgeMetadata = new ConcurrentHashMap<>();

    // 内存向量存储（ChromaDB 不可用时的降级方案）
    private final Map<String, float[]> noteEmbeddings = new ConcurrentHashMap<>();
    private final Map<String, float[]> knowledgeEmbeddings = new ConcurrentHashMap<>();

    public VectorStoreService(ApplicationProperties props, ModelFactory modelFactory, Bm25Service bm25Service) {
        this.props = props;
        this.embeddingModel = modelFactory.createEmbeddingModel();
        this.bm25Service = bm25Service;

        // 尝试连接 ChromaDB
        try {
            String chromaUrl = props.getChroma().getUrl();
            this.noteStore = ChromaEmbeddingStore.builder()
                    .baseUrl(chromaUrl)
                    .collectionName(props.getChroma().getNotesCollection())
                    .build();
            this.knowledgeStore = ChromaEmbeddingStore.builder()
                    .baseUrl(chromaUrl)
                    .collectionName(props.getChroma().getCollection())
                    .build();
            this.chromaAvailable = true;
            log.info("ChromaDB 向量存储已连接: {}", chromaUrl);
        } catch (Exception e) {
            log.warn("ChromaDB 连接失败，降级为内存向量存储: {}", e.getMessage());
            this.noteStore = null;
            this.knowledgeStore = null;
        }
    }

    // ========== 笔记向量操作 ==========

    public void addNoteVector(Note note) {
        String text = buildNoteText(note);
        Embedding embedding = embed(text);

        Map<String, Object> meta = new HashMap<>();
        meta.put("note_id", note.getId());
        meta.put("user_id", note.getUserId());
        meta.put("doc_type", "note");
        meta.put("title", note.getTitle() != null ? note.getTitle() : "");
        meta.put("content", note.getContent() != null ? note.getContent() : "");

        noteMetadata.put(note.getId(), meta);

        if (chromaAvailable) {
            TextSegment segment = TextSegment.from(text, dev.langchain4j.data.document.Metadata.from(meta));
            noteStore.add(embedding, segment);
        } else {
            noteEmbeddings.put(note.getId(), embedding.vector());
        }

        bm25Service.addDocument(note.getUserId(), note.getId(), text,
                Map.of("source", "note", "note_id", note.getId(),
                        "title", note.getTitle(), "user_id", note.getUserId()));

        log.debug("Added note vector + BM25: {} (chroma={})", note.getId(), chromaAvailable);
    }

    public void deleteNoteVector(String noteId) {
        if (chromaAvailable) {
            noteStore.remove(noteId);
        }
        noteEmbeddings.remove(noteId);
        Map<String, Object> removed = noteMetadata.remove(noteId);
        if (removed != null) {
            bm25Service.deleteDocument((String) removed.get("user_id"), noteId);
        }
    }

    public List<Map<String, Object>> searchNotes(String userId, String query, int topK) {
        Embedding queryEmbedding = embed(query);

        if (chromaAvailable) {
            return searchChroma(noteStore, queryEmbedding, userId, topK, "note");
        } else {
            return searchMemory(noteEmbeddings, noteMetadata, queryEmbedding.vector(), userId, topK, "note");
        }
    }

    // ========== 知识库向量操作 ==========

    public void addKnowledgeDocument(String userId, String filename, String md5,
                                     List<String> chunks, Map<String, Object> metadata) {
        List<Embedding> embeddings = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String key = md5 + "_" + i;
            String content = chunks.get(i);
            Embedding embedding = embed(content);

            Map<String, Object> meta = new HashMap<>();
            meta.put("chunk_id", key);
            meta.put("user_id", userId);
            meta.put("filename", filename);
            meta.put("md5", md5);
            meta.put("index", i);
            meta.put("content", content);

            knowledgeMetadata.put(key, meta);

            if (chromaAvailable) {
                embeddings.add(embedding);
                segments.add(TextSegment.from(content, dev.langchain4j.data.document.Metadata.from(meta)));
            } else {
                knowledgeEmbeddings.put(key, embedding.vector());
            }

            bm25Service.addDocument(userId, key, content,
                    Map.of("source", "knowledge_base", "chunk_id", key,
                            "filename", filename, "md5", md5, "user_id", userId));
        }

        if (chromaAvailable && !embeddings.isEmpty()) {
            knowledgeStore.addAll(embeddings, segments);
        }

        log.debug("Added {} chunks: {} (chroma={})", chunks.size(), filename, chromaAvailable);
    }

    public boolean hasKnowledgeDocument(String userId, String md5) {
        return knowledgeMetadata.values().stream()
                .anyMatch(v -> userId.equals(v.get("user_id")) && md5.equals(v.get("md5")));
    }

    public void deleteUserKnowledge(String userId) {
        knowledgeMetadata.entrySet().removeIf(e -> userId.equals(e.getValue().get("user_id")));
        knowledgeEmbeddings.entrySet().removeIf(e -> {
            Map<String, Object> meta = knowledgeMetadata.get(e.getKey());
            return meta != null && userId.equals(meta.get("user_id"));
        });
        bm25Service.clearUserIndex(userId);
    }

    public void deleteKnowledgeByFilename(String userId, String filename) {
        Set<String> keysToRemove = knowledgeMetadata.entrySet().stream()
                .filter(e -> userId.equals(e.getValue().get("user_id")) && filename.equals(e.getValue().get("filename")))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        keysToRemove.forEach(k -> {
            knowledgeMetadata.remove(k);
            knowledgeEmbeddings.remove(k);
        });
    }

    public List<Map<String, Object>> searchKnowledge(String userId, String query, int topK) {
        Embedding queryEmbedding = embed(query);

        if (chromaAvailable) {
            return searchChroma(knowledgeStore, queryEmbedding, userId, topK, "knowledge_base");
        } else {
            return searchMemory(knowledgeEmbeddings, knowledgeMetadata, queryEmbedding.vector(), userId, topK, "knowledge_base");
        }
    }

    // ========== 文档查询（基于元数据缓存） ==========

    public List<Map<String, Object>> getUserDocuments(String userId) {
        Map<String, Map<String, Object>> docGroups = new LinkedHashMap<>();
        knowledgeMetadata.values().stream()
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
                    ((List<String>) doc.get("chunks")).add((String) v.get("content"));
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
        List<Map<String, Object>> chunks = knowledgeMetadata.values().stream()
                .filter(v -> userId.equals(v.get("user_id")) && filename.equals(v.get("filename")))
                .sorted(Comparator.comparingInt(v -> (int) v.getOrDefault("index", 0)))
                .toList();
        if (chunks.isEmpty()) return null;

        StringBuilder content = new StringBuilder();
        List<Map<String, Object>> chunkDetails = new ArrayList<>();
        for (Map<String, Object> chunk : chunks) {
            content.append(chunk.get("content"));
            chunkDetails.add(Map.of("chunk_id", chunk.get("chunk_id"), "index", chunk.get("index"), "content", chunk.get("content")));
        }

        Map<String, Object> first = chunks.get(0);
        return Map.of("id", first.get("md5"), "filename", filename, "user_id", userId,
                "chunk_count", chunks.size(), "content", content.toString(), "chunks", chunkDetails, "images", List.of());
    }

    public Map<String, Object> getDocumentChunks(String userId, String filename) {
        List<Map<String, Object>> chunks = knowledgeMetadata.values().stream()
                .filter(v -> userId.equals(v.get("user_id")) && filename.equals(v.get("filename")))
                .sorted(Comparator.comparingInt(v -> (int) v.getOrDefault("index", 0)))
                .map(v -> Map.<String, Object>of("chunk_id", v.get("chunk_id"), "index", v.get("index"),
                        "content", v.get("content"), "metadata", Map.of(), "images", List.of()))
                .toList();
        return Map.of("filename", filename, "total_chunks", chunks.size(), "chunks", chunks);
    }

    // ========== 检索工具方法 ==========

    /** ChromaDB 向量检索 */
    private List<Map<String, Object>> searchChroma(EmbeddingStore<TextSegment> store,
                                                    Embedding queryEmbedding, String userId,
                                                    int topK, String sourceType) {
        Filter filter = new IsEqualTo("user_id", userId);
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .filter(filter)
                .build();
        EmbeddingSearchResult<TextSegment> results = store.search(request);

        return results.matches().stream().map(match -> {
            Map<String, Object> result = new HashMap<>();
            match.embedded().metadata().asMap().forEach((k, v) -> result.put(k, String.valueOf(v)));
            result.put("distance", 1.0f - match.score());
            result.put("similarity", match.score());
            result.put("source_type", sourceType);
            return result;
        }).collect(Collectors.toList());
    }

    /** 内存向量检索（降级方案） */
    private List<Map<String, Object>> searchMemory(Map<String, float[]> embeddings,
                                                     Map<String, Map<String, Object>> metadata,
                                                     float[] queryVector, String userId,
                                                     int topK, String sourceType) {
        return metadata.entrySet().stream()
                .filter(e -> userId.equals(e.getValue().get("user_id")))
                .filter(e -> embeddings.containsKey(e.getKey()))
                .map(e -> {
                    float[] docVector = embeddings.get(e.getKey());
                    float similarity = cosineSimilarity(queryVector, docVector);
                    Map<String, Object> result = new HashMap<>(e.getValue());
                    result.put("distance", 1.0f - similarity);
                    result.put("similarity", similarity);
                    result.put("source_type", sourceType);
                    return result;
                })
                .sorted((a, b) -> Float.compare((float) a.get("distance"), (float) b.get("distance")))
                .limit(topK)
                .collect(Collectors.toList());
    }

    // ========== 工具方法 ==========

    private Embedding embed(String text) {
        try {
            Response<Embedding> response = embeddingModel.embed(TextSegment.from(text));
            return response.content();
        } catch (Exception e) {
            log.warn("文本向量化失败，使用零向量兜底: {}", e.getMessage());
            return Embedding.from(new float[1024]);
        }
    }

    private String buildNoteText(Note note) {
        StringBuilder sb = new StringBuilder();
        if (note.getTitle() != null && !note.getTitle().isEmpty()) sb.append(note.getTitle()).append("\n");
        if (note.getContent() != null) {
            String content = note.getContent().length() > 1000 ? note.getContent().substring(0, 1000) : note.getContent();
            sb.append(content);
        }
        return sb.toString();
    }

    private float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0f;
        float dot = 0f, normA = 0f, normB = 0f;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i]; }
        if (normA == 0f || normB == 0f) return 0f;
        return dot / (float) (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
