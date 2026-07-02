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
import dev.langchain4j.store.embedding.filter.logical.And;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 向量存储服务
 * - 向量检索：直接走 ChromaDB
 * - 文档列表/详情：从 Md5Store + ChromaDB 组合获取
 * - ChromaDB 不可用时降级为内存存储
 */
@Slf4j
@Service
public class VectorStoreService {

    private final ApplicationProperties props;
    private final EmbeddingModel embeddingModel;
    private final Bm25Service bm25Service;
    private final Md5Store md5Store;

    private EmbeddingStore<TextSegment> noteStore;
    private EmbeddingStore<TextSegment> knowledgeStore;
    private boolean chromaAvailable = false;

    // 降级方案：ChromaDB 不可用时使用内存存储
    private final Map<String, Map<String, Object>> fallbackNoteData = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> fallbackKnowledgeData = new ConcurrentHashMap<>();
    private final Map<String, float[]> fallbackNoteEmbeddings = new ConcurrentHashMap<>();
    private final Map<String, float[]> fallbackKnowledgeEmbeddings = new ConcurrentHashMap<>();

    public VectorStoreService(ApplicationProperties props, ModelFactory modelFactory,
                              Bm25Service bm25Service, Md5Store md5Store) {
        this.props = props;
        this.embeddingModel = modelFactory.createEmbeddingModel();
        this.bm25Service = bm25Service;
        this.md5Store = md5Store;

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
            log.info("ChromaDB 已连接: {}", chromaUrl);
        } catch (Exception e) {
            log.warn("ChromaDB 连接失败，降级为内存存储: {}", e.getMessage());
        }
    }

    // ========== 文档列表查询（从 Md5Store 获取文件列表） ==========

    /**
     * 获取用户的知识库文档列表
     * 数据源：Md5Store（持久化，启动即加载）+ ChromaDB（获取 chunk 数量和预览）
     */
    public List<Map<String, Object>> getUserDocuments(String userId) {
        List<Map<String, String>> md5Records = md5Store.getUserRecords(userId);
        List<Map<String, Object>> documents = new ArrayList<>();

        for (Map<String, String> record : md5Records) {
            String filename = record.get("filename");
            String md5 = record.get("md5");

            Map<String, Object> doc = new HashMap<>();
            doc.put("filename", filename);
            doc.put("user_id", userId);
            doc.put("md5", md5);

            if (chromaAvailable) {
                try {
                    List<Map<String, Object>> chunks = queryChunksByMd5(userId, md5);
                    doc.put("chunk_count", chunks.size());
                    String preview = chunks.isEmpty() ? "" :
                            ((String) chunks.get(0).get("content")).substring(0, Math.min(200, ((String) chunks.get(0).get("content")).length()));
                    doc.put("preview", preview);
                } catch (Exception e) {
                    log.warn("查询文档 '{}' 的 chunk 失败: {}", filename, e.getMessage());
                    doc.put("chunk_count", 0);
                    doc.put("preview", "");
                }
            } else {
                // 降级：从内存获取
                long count = fallbackKnowledgeData.values().stream()
                        .filter(v -> userId.equals(v.get("user_id")) && md5.equals(v.get("md5")))
                        .count();
                doc.put("chunk_count", (int) count);
                doc.put("preview", "");
            }

            documents.add(doc);
        }

        return documents;
    }

    /**
     * 获取文档详情（从 ChromaDB 查询该文件的所有 chunk）
     */
    public Map<String, Object> getDocumentDetail(String userId, String filename) {
        Map<String, String> md5Record = md5Store.getUserRecords(userId).stream()
                .filter(r -> filename.equals(r.get("filename")))
                .findFirst().orElse(null);
        if (md5Record == null) return null;

        String md5 = md5Record.get("md5");
        List<Map<String, Object>> chunks;

        try {
            if (chromaAvailable) {
                chunks = queryChunksByMd5(userId, md5);
            } else {
                chunks = fallbackKnowledgeData.values().stream()
                        .filter(v -> userId.equals(v.get("user_id")) && md5.equals(v.get("md5")))
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("查询文档详情失败（ChromaDB 可能不可用）: {}", e.getMessage());
            return Map.of("id", md5, "filename", filename, "user_id", userId,
                    "chunk_count", 0, "content", "（向量数据暂不可用）",
                    "chunks", List.of(), "images", List.of());
        }

        if (chunks.isEmpty()) return null;

        chunks.sort(Comparator.comparingInt(v -> {
            Object idx = v.getOrDefault("index", "0");
            return idx instanceof Number ? ((Number) idx).intValue() : (int) Double.parseDouble(String.valueOf(idx));
        }));

        StringBuilder content = new StringBuilder();
        List<Map<String, Object>> chunkDetails = new ArrayList<>();
        for (Map<String, Object> chunk : chunks) {
            content.append(chunk.get("content"));
            chunkDetails.add(Map.of("chunk_id", chunk.get("chunk_id"),
                    "index", chunk.get("index"), "content", chunk.get("content")));
        }

        return Map.of("id", md5, "filename", filename, "user_id", userId,
                "chunk_count", chunks.size(), "content", content.toString(),
                "chunks", chunkDetails, "images", List.of());
    }

    /**
     * 获取文档的切片列表
     */
    public Map<String, Object> getDocumentChunks(String userId, String filename) {
        Map<String, String> md5Record = md5Store.getUserRecords(userId).stream()
                .filter(r -> filename.equals(r.get("filename")))
                .findFirst().orElse(null);
        if (md5Record == null) return Map.of("filename", filename, "total_chunks", 0, "chunks", List.of());

        String md5 = md5Record.get("md5");
        List<Map<String, Object>> chunks;

        if (chromaAvailable) {
            chunks = queryChunksByMd5(userId, md5);
        } else {
            chunks = fallbackKnowledgeData.values().stream()
                    .filter(v -> userId.equals(v.get("user_id")) && md5.equals(v.get("md5")))
                    .collect(Collectors.toList());
        }

        chunks.sort(Comparator.comparingInt(v -> {
            Object idx = v.getOrDefault("index", "0");
            return idx instanceof Number ? ((Number) idx).intValue() : (int) Double.parseDouble(String.valueOf(idx));
        }));

        List<Map<String, Object>> chunkList = chunks.stream()
                .map(v -> Map.<String, Object>of("chunk_id", v.get("chunk_id"), "index", v.get("index"),
                        "content", v.get("content"), "metadata", Map.of(), "images", List.of()))
                .toList();

        return Map.of("filename", filename, "total_chunks", chunkList.size(), "chunks", chunkList);
    }

    /**
     * 检查文档是否存在（从 Md5Store 判断）
     */
    public boolean hasKnowledgeDocument(String userId, String md5) {
        return md5Store.getRecord(md5, userId) != null;
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

        if (chromaAvailable) {
            TextSegment segment = TextSegment.from(text, dev.langchain4j.data.document.Metadata.from(meta));
            noteStore.add(embedding, segment);
        } else {
            fallbackNoteData.put(note.getId(), meta);
            fallbackNoteEmbeddings.put(note.getId(), embedding.vector());
        }

        bm25Service.addDocument(note.getUserId(), note.getId(), text,
                Map.of("source", "note", "note_id", note.getId(),
                        "title", note.getTitle(), "user_id", note.getUserId()));
    }

    public void deleteNoteVector(String noteId) {
        if (chromaAvailable) {
            noteStore.remove(noteId);
        } else {
            fallbackNoteData.remove(noteId);
            fallbackNoteEmbeddings.remove(noteId);
        }
    }

    public List<Map<String, Object>> searchNotes(String userId, String query, int topK) {
        Embedding queryEmbedding = embed(query);
        if (chromaAvailable) {
            return searchChroma(noteStore, queryEmbedding, userId, topK, "note");
        } else {
            return searchFallback(fallbackNoteEmbeddings, fallbackNoteData,
                    queryEmbedding.vector(), userId, topK, "note");
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

            if (chromaAvailable) {
                embeddings.add(embedding);
                segments.add(TextSegment.from(content, dev.langchain4j.data.document.Metadata.from(meta)));
            } else {
                fallbackKnowledgeData.put(key, meta);
                fallbackKnowledgeEmbeddings.put(key, embedding.vector());
            }

            bm25Service.addDocument(userId, key, content,
                    Map.of("source", "knowledge_base", "chunk_id", key,
                            "filename", filename, "md5", md5, "user_id", userId));
        }

        if (chromaAvailable && !embeddings.isEmpty()) {
            try {
                knowledgeStore.addAll(embeddings, segments);
            } catch (Exception e) {
                log.warn("ChromaDB 存储失败（可能是维度不匹配）: {}", e.getMessage());
            }
        }
    }

    public void deleteUserKnowledge(String userId) {
        try {
            if (chromaAvailable) {
                List<Map<String, String>> records = md5Store.getUserRecords(userId);
                for (Map<String, String> record : records) {
                    deleteChunksByMd5(userId, record.get("md5"));
                }
            } else {
                fallbackKnowledgeData.entrySet().removeIf(e -> userId.equals(e.getValue().get("user_id")));
                fallbackKnowledgeEmbeddings.clear();
            }
        } catch (Exception e) {
            log.warn("ChromaDB 删除失败（不影响 Md5Store 清理）: {}", e.getMessage());
        }
        md5Store.deleteByUser(userId);
        bm25Service.clearUserIndex(userId);
    }

    public void deleteKnowledgeByFilename(String userId, String filename) {
        Map<String, String> md5Record = md5Store.getUserRecords(userId).stream()
                .filter(r -> filename.equals(r.get("filename")))
                .findFirst().orElse(null);
        if (md5Record == null) return;

        String md5 = md5Record.get("md5");
        try {
            if (chromaAvailable) {
                deleteChunksByMd5(userId, md5);
            } else {
                fallbackKnowledgeData.entrySet().removeIf(e ->
                        userId.equals(e.getValue().get("user_id")) && md5.equals(e.getValue().get("md5")));
            }
        } catch (Exception e) {
            log.warn("ChromaDB 删除失败（不影响 Md5Store 清理）: {}", e.getMessage());
        }
        // 无论 ChromaDB 是否成功，都清理 Md5Store 记录
        md5Store.deleteByMd5(md5, userId);
    }

    public List<Map<String, Object>> searchKnowledge(String userId, String query, int topK) {
        Embedding queryEmbedding = embed(query);
        if (chromaAvailable) {
            return searchChroma(knowledgeStore, queryEmbedding, userId, topK, "knowledge_base");
        } else {
            return searchFallback(fallbackKnowledgeEmbeddings, fallbackKnowledgeData,
                    queryEmbedding.vector(), userId, topK, "knowledge_base");
        }
    }

    // ========== ChromaDB 工具方法 ==========

    /** 按 user_id + md5 双重过滤查询某个文件的所有 chunk */
    private List<Map<String, Object>> queryChunksByMd5(String userId, String md5) {
        try {
            // 用真实的 embedding 来查询，确保维度匹配
            Embedding queryEmbedding = embed("query");
            Filter filter = new And(
                    new IsEqualTo("user_id", userId),
                    new IsEqualTo("md5", md5)
            );
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(500)
                    .filter(filter)
                    .build();
            EmbeddingSearchResult<TextSegment> results = knowledgeStore.search(request);

            return results.matches().stream()
                    .map(match -> {
                        Map<String, Object> meta = new HashMap<>();
                        match.embedded().metadata().asMap().forEach((k, v) -> meta.put(k, String.valueOf(v)));
                        return meta;
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("ChromaDB 查询失败（可能是维度不匹配或服务不可用）: {}", e.getMessage());
            return List.of();
        }
    }

    /** 按 MD5 删除某个文件的所有 chunk */
    private void deleteChunksByMd5(String userId, String md5) {
        List<Map<String, Object>> chunks = queryChunksByMd5(userId, md5);
        for (Map<String, Object> chunk : chunks) {
            String chunkId = (String) chunk.get("chunk_id");
            if (chunkId != null) {
                knowledgeStore.remove(chunkId);
            }
        }
    }

    /** ChromaDB 向量检索 */
    private List<Map<String, Object>> searchChroma(EmbeddingStore<TextSegment> store,
                                                    Embedding queryEmbedding, String userId,
                                                    int topK, String sourceType) {
        try {
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
        } catch (Exception e) {
            log.warn("ChromaDB 向量检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 内存降级检索 */
    private List<Map<String, Object>> searchFallback(Map<String, float[]> embeddings,
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

    private Embedding embed(String text) {
        try {
            Response<Embedding> response = embeddingModel.embed(TextSegment.from(text));
            return response.content();
        } catch (Exception e) {
            log.warn("文本向量化失败: {}", e.getMessage());
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
