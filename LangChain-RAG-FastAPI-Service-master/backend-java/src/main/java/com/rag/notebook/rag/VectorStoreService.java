package com.rag.notebook.rag;

import com.rag.notebook.agent.ModelFactory;
import com.rag.notebook.config.ApplicationProperties;
import com.rag.notebook.knowledge.entity.ChromaCleanupTask;
import com.rag.notebook.knowledge.entity.KnowledgeDocument;
import com.rag.notebook.knowledge.entity.KnowledgeDocumentChunk;
import com.rag.notebook.knowledge.repository.ChromaCleanupTaskRepository;
import com.rag.notebook.knowledge.repository.KnowledgeDocumentChunkRepository;
import com.rag.notebook.knowledge.repository.KnowledgeDocumentRepository;
import com.rag.notebook.note.entity.Note;
import com.rag.notebook.note.entity.NoteChunk;
import com.rag.notebook.note.repository.NoteChunkRepository;
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
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.logical.Or;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VectorStoreService {

    private static final String COLLECTION_ID_CACHE_PREFIX = "chroma:collection:id:";
    private static final long COLLECTION_ID_CACHE_TTL_HOURS = 24;
    private static final Duration CHROMA_TIMEOUT = Duration.ofMinutes(2);
    private static final int EMBEDDING_BATCH_SIZE = 3;
    private static final int EMBEDDING_RETRIES = 5;
    private static final long EMBEDDING_RETRY_BASE_DELAY_MS = 1000L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApplicationProperties props;
    private final EmbeddingModel embeddingModel;
    private final Bm25Service bm25Service;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeDocumentChunkRepository chunkRepository;
    private final ChromaCleanupTaskRepository cleanupTaskRepository;
    private final StringRedisTemplate redisTemplate;
    private final Md5Store md5Store;
    private final NoteChunkRepository noteChunkRepository;
    private final DocumentTaskExecutor documentTaskExecutor;

    // ChromaDB 向量存储（连接成功时使用）
    private EmbeddingStore<TextSegment> noteStore;
    private EmbeddingStore<TextSegment> knowledgeStore;
    private boolean chromaAvailable = false;

    public VectorStoreService(ApplicationProperties props, ModelFactory modelFactory, Bm25Service bm25Service,
                              KnowledgeDocumentRepository documentRepository,
                              KnowledgeDocumentChunkRepository chunkRepository,
                              ChromaCleanupTaskRepository cleanupTaskRepository,
                              StringRedisTemplate redisTemplate,
                              Md5Store md5Store,
                              NoteChunkRepository noteChunkRepository,
                              DocumentTaskExecutor documentTaskExecutor) {
        this.props = props;
        this.embeddingModel = modelFactory.createEmbeddingModel();
        this.bm25Service = bm25Service;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.cleanupTaskRepository = cleanupTaskRepository;
        this.redisTemplate = redisTemplate;
        this.md5Store = md5Store;
        this.noteChunkRepository = noteChunkRepository;
        this.documentTaskExecutor = documentTaskExecutor;

        // 尝试连接 ChromaDB
        try {
            String chromaUrl = props.getChroma().getUrl();
            this.noteStore = ChromaEmbeddingStore.builder()
                    .baseUrl(chromaUrl)
                    .collectionName(props.getChroma().getNotesCollection())
                    .timeout(CHROMA_TIMEOUT)
                    .build();
            this.knowledgeStore = ChromaEmbeddingStore.builder()
                    .baseUrl(chromaUrl)
                    .collectionName(props.getChroma().getCollection())
                    .timeout(CHROMA_TIMEOUT)
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

    /**
     * 添加笔记向量（与知识库流程一致：切片→MySQL+ChromaDB+BM25）
     */
    @Transactional
    public void addNoteVector(Note note) {
        String text = buildNoteText(note);
        String title = note.getTitle() != null ? note.getTitle() : "";

        // 1. 文本切片
        List<RagChunk> chunks = RagChunker.splitNote(note.getId(), title, note.getContent(),
                props.getChroma().getChunkSize(), props.getChroma().getChunkOverlap());
        log.info("笔记切片完成: noteId={}, chunks={}", note.getId(), chunks.size());

        // 2. 写入 MySQL（NoteChunk 表）
        List<NoteChunk> chunkEntities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            NoteChunk chunk = new NoteChunk();
            chunk.setId(UUID.randomUUID().toString());
            chunk.setNoteId(note.getId());
            chunk.setChunkIndex(i);
            RagChunk ragChunk = chunks.get(i);
            chunk.setContent(ragChunk.getContent());
            chunk.setRetrievalText(ragChunk.getRetrievalText());
            chunk.setContentType(ragChunk.getContentType());
            chunk.setSectionPath(ragChunk.getSectionPath());
            chunk.setPreviousChunkId(ragChunk.getPreviousChunkId());
            chunk.setNextChunkId(ragChunk.getNextChunkId());
            chunkEntities.add(chunk);
        }
        noteChunkRepository.saveAll(chunkEntities);

        // 3. 写入 BM25 索引（不依赖 ChromaDB）
        for (int i = 0; i < chunks.size(); i++) {
            String chunkKey = note.getId() + "_" + i;
            RagChunk chunk = chunks.get(i);
            Map<String, Object> bm25Metadata = new HashMap<>();
            bm25Metadata.put("source", "note");
            bm25Metadata.put("chunk_id", chunkKey);
            bm25Metadata.put("note_id", note.getId());
            bm25Metadata.put("title", title);
            bm25Metadata.put("user_id", note.getUserId());
            bm25Metadata.putAll(chunk.toMetadataMap());
            bm25Service.addDocument(note.getUserId(), chunkKey, chunk.getRetrievalText(), bm25Metadata);
        }

        // 4. 异步写入 ChromaDB（可选，失败不影响 BM25）
        if (chromaAvailable) {
            writeNoteToChromaAsync(note.getId(), note.getUserId(), title, chunks);
        } else {
            log.warn("ChromaDB不可用，跳过向量索引: noteId={}", note.getId());
        }

        log.info("笔记索引完成: noteId={}, chunks={}", note.getId(), chunks.size());
    }

    /**
     * 异步写入笔记向量到 ChromaDB
     */
    @Async
    public void writeNoteToChromaAsync(String noteId, String userId, String title, List<RagChunk> chunks) {
        try {
            // 1. 清理旧数据
            deleteNoteChunksFromChroma(noteId);

            // 2. 批量写入新数据
            List<TextSegment> segments = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                String chunkKey = noteId + "_" + i;
                RagChunk chunk = chunks.get(i);
                String content = chunk.getContent();
                String retrievalText = chunk.getRetrievalText();

                Map<String, Object> meta = new HashMap<>();
                meta.put("chunk_id", chunkKey);
                meta.put("note_id", noteId);
                meta.put("user_id", userId);
                meta.put("title", title);
                meta.put("content", content);
                meta.put("retrieval_text", retrievalText);
                meta.put("index", i);
                meta.put("source", "note");
                meta.put("content_type", chunk.getContentType());
                meta.put("section_path", chunk.getSectionPath());
                if (chunk.getPreviousChunkId() != null) meta.put("previous_chunk_id", chunk.getPreviousChunkId());
                if (chunk.getNextChunkId() != null) meta.put("next_chunk_id", chunk.getNextChunkId());

                segments.add(TextSegment.from(retrievalText, dev.langchain4j.data.document.Metadata.from(meta)));
            }

            // 分批写入
            int batchSize = EMBEDDING_BATCH_SIZE;
            for (int i = 0; i < segments.size(); i += batchSize) {
                int end = Math.min(i + batchSize, segments.size());
                List<TextSegment> batch = segments.subList(i, end);
                List<String> batchContents = chunks.subList(i, end).stream()
                        .map(RagChunk::getRetrievalText)
                        .collect(Collectors.toList());
                List<Embedding> embeddings = batchEmbed(batchContents);
                noteStore.addAll(embeddings, batch);
            }

            log.info("笔记向量写入ChromaDB成功: noteId={}, chunks={}", noteId, chunks.size());
        } catch (Exception e) {
            log.error("笔记向量写入ChromaDB失败: noteId={}, error={}", noteId, e.getMessage());
        }
    }

    /**
     * 从 ChromaDB 删除笔记的所有切片
     */
    private void deleteNoteChunksFromChroma(String noteId) {
        String chromaUrl = props.getChroma().getUrl();
        String collectionName = props.getChroma().getNotesCollection();

        // 第一次尝试（可能使用缓存的 collection ID）
        boolean success = tryDeleteFromChroma(chromaUrl, collectionName, noteId, false);
        if (success) return;

        // 第二次尝试：清除缓存，重新获取 collection ID
        log.info("ChromaDB 删除失败，清除缓存后重试: noteId={}", noteId);
        clearCollectionIdCache(collectionName);
        tryDeleteFromChroma(chromaUrl, collectionName, noteId, true);
    }

    private boolean tryDeleteFromChroma(String chromaUrl, String collectionName, String noteId, boolean isRetry) {
        try {
            String collectionId = getCollectionId(collectionName);
            if (collectionId == null) {
                log.error("无法获取笔记Collection ID{}", isRetry ? "（重试）" : "");
                return false;
            }

            String deleteUrl = chromaUrl + "/api/v1/collections/" + collectionId + "/delete";
            String requestBody = String.format("{\"where\": {\"note_id\": \"%s\"}}", noteId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.exchange(deleteUrl, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                log.info("笔记向量删除成功{}: noteId={}", isRetry ? "（重试）" : "", noteId);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("笔记向量删除{}失败: noteId={}, error={}", isRetry ? "重试" : "尝试", noteId, e.getMessage());
            return false;
        }
    }

    /**
     * 删除笔记向量（删除所有 chunk）
     */
    @Transactional
    public void deleteNoteVector(String noteId, String userId) {
        // 1. 先查询 chunks（用于 BM25 删除，必须在 MySQL 删除前查询）
        List<NoteChunk> chunks = noteChunkRepository.findByNoteIdOrderByChunkIndexAsc(noteId);

        // 2. 从 BM25 索引删除（在 MySQL 删除前，因为需要 chunkIndex）
        for (NoteChunk chunk : chunks) {
            String chunkKey = noteId + "_" + chunk.getChunkIndex();
            bm25Service.deleteDocument(userId, chunkKey);
        }
        log.info("BM25索引已删除: noteId={}, chunks={}", noteId, chunks.size());

        // 3. 从 MySQL 删除 NoteChunk
        noteChunkRepository.deleteByNoteId(noteId);
        log.info("笔记切片已从MySQL删除: noteId={}", noteId);

        // 4. 从 ChromaDB 删除（按 note_id 批量删除）
        if (chromaAvailable) {
            try {
                deleteNoteChunksFromChroma(noteId);
                log.info("笔记向量已从ChromaDB删除: noteId={}", noteId);
            } catch (Exception e) {
                log.error("笔记向量删除失败，记录待清理任务: noteId={}, error={}", noteId, e.getMessage());
                saveNoteCleanupTask(noteId, userId, props.getChroma().getNotesCollection());
            }
        }
    }

    /**
     * 保存笔记清理任务
     */
    private void saveNoteCleanupTask(String noteId, String userId, String collectionName) {
        try {
            ChromaCleanupTask task = new ChromaCleanupTask();
            task.setDocId(noteId);
            task.setUserId(userId);
            task.setCollectionName(collectionName);
            task.setTaskType("note");
            task.setStatus("pending");
            cleanupTaskRepository.save(task);
            log.info("已保存笔记清理任务: noteId={}", noteId);
        } catch (Exception e) {
            log.error("保存笔记清理任务失败: noteId={}, error={}", noteId, e.getMessage());
        }
    }

    /**
     * 从笔记向量库删除数据（供 Scheduler 调用）
     */
    public void deleteNoteFromStore(String noteId) {
        if (noteStore != null) {
            noteStore.remove(noteId);
        }
    }

    /**
     * 检索笔记（只使用 ChromaDB，与知识库一致）
     */
    public List<Map<String, Object>> searchNotes(String userId, String query, int topK) {
        if (!chromaAvailable) {
            log.warn("ChromaDB不可用，无法检索笔记");
            return List.of();
        }

        Embedding queryEmbedding = embed(query);
        return searchChroma(noteStore, queryEmbedding, userId, topK, "note");
    }

    // ========== 知识库向量操作（MySQL + BM25，ChromaDB 由 DocumentTaskExecutor 异步执行） ==========

    @Transactional
    public String addKnowledgeDocument(String userId, String filename, String md5,
                                        List<String> chunks, Map<String, Object> metadata) {
        String originalFilename = (String) metadata.getOrDefault("original_filename", filename);
        Long fileSize = metadata.containsKey("file_size") ? (Long) metadata.get("file_size") : 0L;
        return addKnowledgeDocument(userId, filename, originalFilename, md5, fileSize, chunks, null);
    }

    @Transactional
    public String addKnowledgeDocument(String userId, String filename, String md5,
                                        List<String> chunks, Map<String, Object> metadata,
                                        BiConsumer<String, Object> progressCallback) {
        String originalFilename = (String) metadata.getOrDefault("original_filename", filename);
        Long fileSize = metadata.containsKey("file_size") ? (Long) metadata.get("file_size") : 0L;
        return addKnowledgeDocument(userId, filename, originalFilename, md5, fileSize, chunks, progressCallback);
    }

    @Transactional
    public String addKnowledgeDocumentChunks(String userId, String filename, String md5,
                                             List<RagChunk> chunks, Map<String, Object> metadata,
                                             BiConsumer<String, Object> progressCallback) {
        String originalFilename = (String) metadata.getOrDefault("original_filename", filename);
        Long fileSize = metadata.containsKey("file_size") ? (Long) metadata.get("file_size") : 0L;

        log.info("开始添加结构化知识文档: userId={}, filename={}, md5={}, chunks={}",
                userId, filename, md5, chunks.size());

        if (documentRepository.existsByUserIdAndMd5(userId, md5)) {
            log.info("文档已存在（MD5重复），跳过写入: userId={}, md5={}, filename={}", userId, md5, filename);
            if (progressCallback != null) {
                progressCallback.accept("skipping", originalFilename);
            }
            return null;
        }

        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(UUID.randomUUID().toString());
        document.setUserId(userId);
        document.setFilename(filename);
        document.setOriginalFilename(originalFilename);
        document.setMd5(md5);
        document.setFileSize(fileSize);
        document.setChunkCount(chunks.size());
        document.setStatus("processing");
        document.setPreview(chunks.isEmpty() ? "" : chunks.get(0).getContent().substring(0,
                Math.min(200, chunks.get(0).getContent().length())));
        document = documentRepository.save(document);

        List<KnowledgeDocumentChunk> chunkEntities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RagChunk ragChunk = chunks.get(i);
            String parentId = document.getId() + "_p_" + (ragChunk.getParentIndex() != null ? ragChunk.getParentIndex() : i);
            ragChunk.setParentId(parentId);

            KnowledgeDocumentChunk chunk = new KnowledgeDocumentChunk();
            chunk.setId(UUID.randomUUID().toString());
            chunk.setDocument(document);
            chunk.setChunkIndex(i);
            chunk.setContent(ragChunk.getContent());
            chunk.setRetrievalText(ragChunk.getRetrievalText());
            chunk.setContentType(ragChunk.getContentType());
            chunk.setSectionPath(ragChunk.getSectionPath());
            chunk.setParentId(parentId);
            chunk.setPageStart(ragChunk.getPageStart());
            chunk.setPageEnd(ragChunk.getPageEnd());
            chunk.setMetadataJson(ragChunk.toMetadataMap());
            chunkEntities.add(chunk);
        }
        chunkRepository.saveAll(chunkEntities);
        log.info("MySQL结构化文档+切片保存成功: docId={}, chunks={}", document.getId(), chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            RagChunk chunk = chunks.get(i);
            String key = md5 + "_" + i;
            Map<String, Object> bm25Metadata = new HashMap<>();
            bm25Metadata.put("source", "knowledge_base");
            bm25Metadata.put("chunk_id", key);
            bm25Metadata.put("filename", filename);
            bm25Metadata.put("original_filename", originalFilename);
            bm25Metadata.put("md5", md5);
            bm25Metadata.put("user_id", userId);
            bm25Metadata.put("doc_id", document.getId());
            bm25Metadata.put("index", i);
            bm25Metadata.putAll(chunk.toMetadataMap());
            bm25Service.addDocument(userId, key, chunk.getRetrievalText(), bm25Metadata);
        }

        log.info("结构化知识文档添加完成（MySQL+BM25）: docId={}, filename={}, chunks={}",
                document.getId(), filename, chunks.size());
        return document.getId();
    }

    @Transactional
    public String addKnowledgeDocument(String userId, String filename, String originalFilename,
                                        String md5, Long fileSize, List<String> chunks) {
        return addKnowledgeDocument(userId, filename, originalFilename, md5, fileSize, chunks, null);
    }

    @Transactional
    public String addKnowledgeDocument(String userId, String filename, String originalFilename,
                                        String md5, Long fileSize, List<String> chunks,
                                        BiConsumer<String, Object> progressCallback) {
        log.info("开始添加知识文档: userId={}, filename={}, md5={}, chunks={}", userId, filename, md5, chunks.size());

        // MD5去重检查
        if (documentRepository.existsByUserIdAndMd5(userId, md5)) {
            log.info("文档已存在（MD5重复），跳过写入: userId={}, md5={}, filename={}", userId, md5, filename);
            if (progressCallback != null) {
                progressCallback.accept("skipping", originalFilename);
            }
            return null;
        }

        // 1. 保存文档到MySQL（事务短，仅含MySQL写入）
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(UUID.randomUUID().toString());
        document.setUserId(userId);
        document.setFilename(filename);
        document.setOriginalFilename(originalFilename);
        document.setMd5(md5);
        document.setFileSize(fileSize);
        document.setChunkCount(chunks.size());
        document.setStatus("processing");
        document.setPreview(chunks.isEmpty() ? "" : chunks.get(0).substring(0, Math.min(200, chunks.get(0).length())));
        document = documentRepository.save(document);

        List<KnowledgeDocumentChunk> chunkEntities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeDocumentChunk chunk = new KnowledgeDocumentChunk();
            chunk.setId(UUID.randomUUID().toString());
            chunk.setDocument(document);
            chunk.setChunkIndex(i);
            chunk.setContent(chunks.get(i));
            chunkEntities.add(chunk);
        }
        chunkRepository.saveAll(chunkEntities);
        log.info("MySQL文档+切片保存成功: docId={}, chunks={}", document.getId(), chunks.size());

        // 2. 写入BM25索引（快速）
        for (int i = 0; i < chunks.size(); i++) {
            String key = md5 + "_" + i;
            bm25Service.addDocument(userId, key, chunks.get(i),
                    Map.of("source", "knowledge_base", "chunk_id", key,
                            "filename", filename, "original_filename", originalFilename,
                            "md5", md5, "user_id", userId,
                            "doc_id", document.getId()));
        }

        log.info("知识文档添加完成（MySQL+BM25）: docId={}, filename={}, chunks={}", document.getId(), filename, chunks.size());
        return document.getId();
    }

    /**
     * 从ChromaDB删除指定文档的向量数据
     * 优先删除MySQL，ChromaDB删除失败时记录到清理表
     */
    @Transactional
    public void deleteKnowledgeByFilename(String userId, String filename) {
        // 1. 先查找文档
        Optional<KnowledgeDocument> docOpt = documentRepository.findByUserIdAndFilenameForUpdate(userId, filename);
        if (docOpt.isEmpty()) {
            log.warn("MySQL文档不存在，尝试清理MD5记录: userId={}, filename={}", userId, filename);
            // 即使MySQL记录不存在，也要清理MD5记录（防止残留）
            cleanMd5ByFilename(userId, filename);
            return;
        }

        KnowledgeDocument doc = docOpt.get();
        String docId = doc.getId();
        String md5 = doc.getMd5();

        // 2. 尝试删除ChromaDB，失败则记录待清理任务
        boolean chromaDeleted = deleteFromChromaByDocId(docId);
        if (!chromaDeleted) {
            log.error("ChromaDB删除失败，记录待清理任务: docId={}", docId);
            saveCleanupTask(docId, userId, props.getChroma().getCollection());
        }

        // 3. 删除当前文档的BM25索引
        List<KnowledgeDocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(docId);
        List<String> chunkKeys = new ArrayList<>();
        for (KnowledgeDocumentChunk chunk : chunks) {
            chunkKeys.add(md5 + "_" + chunk.getChunkIndex());
        }
        bm25Service.deleteDocuments(userId, chunkKeys);

        // 4. 删除MySQL（无论ChromaDB是否成功）
        documentRepository.delete(doc);

        // 5. 删除MD5记录
        md5Store.deleteByMd5(md5, userId);

        log.info("文档删除成功: userId={}, filename={}, docId={}, md5={}, chromaDeleted={}", userId, filename, docId, md5, chromaDeleted);
    }

    /**
     * 根据文件名清理 MD5 记录（用于 MySQL 记录已不存在的情况）
     */
    private void cleanMd5ByFilename(String userId, String filename) {
        List<Map<String, String>> records = md5Store.getUserRecords(userId);
        for (Map<String, String> record : records) {
            if (filename.equals(record.get("filename")) || filename.equals(record.get("original_filename"))) {
                md5Store.deleteByMd5(record.get("md5"), userId);
                log.info("已清理残留MD5记录: filename={}, md5={}", filename, record.get("md5"));
            }
        }
    }

    /**
     * 删除用户所有知识库
     */
    @Transactional
    public void deleteUserKnowledge(String userId) {
        // 1. 查找用户所有文档
        List<KnowledgeDocument> documents = documentRepository.findByUserIdOrderByCreatedAtDesc(userId);

        // 2. 尝试删除ChromaDB，失败则记录待清理任务
        for (KnowledgeDocument doc : documents) {
            boolean chromaDeleted = deleteFromChromaByDocId(doc.getId());
            if (!chromaDeleted) {
                log.error("ChromaDB删除失败，记录待清理任务: docId={}", doc.getId());
                saveCleanupTask(doc.getId(), userId, props.getChroma().getCollection());
            }
        }

        // 3. 删除MySQL（无论ChromaDB是否成功）
        documentRepository.deleteAll(documents);

        // 4. 清除BM25索引
        bm25Service.clearUserIndex(userId);

        // 5. 清除MD5记录
        md5Store.deleteByUser(userId);

        log.info("用户所有知识库删除成功: userId={}", userId);
    }

    /**
     * 保存清理任务到数据库
     */
    private void saveCleanupTask(String docId, String userId, String collectionName) {
        try {
            ChromaCleanupTask task = new ChromaCleanupTask();
            task.setDocId(docId);
            task.setUserId(userId);
            task.setCollectionName(collectionName);
            task.setStatus("pending");
            cleanupTaskRepository.save(task);
            log.info("已保存Chroma清理任务: docId={}", docId);
        } catch (Exception e) {
            log.error("保存清理任务失败: docId={}, error={}", docId, e.getMessage());
        }
    }

    /**
     * 从ChromaDB删除指定文档的向量数据（带Redis缓存）
     */
    public boolean deleteFromChromaByDocId(String docId) {
        if (!chromaAvailable) {
            return true;
        }

        try {
            String chromaUrl = props.getChroma().getUrl();
            String collectionName = props.getChroma().getCollection();

            // 1. 获取 collection ID（带Redis缓存）
            String collectionId = getCollectionId(collectionName);
            if (collectionId == null) {
                log.error("无法获取Collection ID: {}", collectionName);
                return false;
            }

            // 2. 构建删除请求
            String deleteUrl = chromaUrl + "/api/v1/collections/" + collectionId + "/delete";
            String requestBody = String.format("{\"where\": {\"doc_id\": \"%s\"}}", docId);

            log.info("Chroma删除请求 - URL: {}, Body: {}", deleteUrl, requestBody);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.exchange(deleteUrl, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                log.info("Chroma删除成功: docId={}", docId);
                return true;
            } else {
                log.warn("Chroma删除失败: docId={}, status={}, body={}", docId, response.getStatusCode(), response.getBody());
                return false;
            }

        } catch (Exception e) {
            // 集合不存在时清缓存
            if (e.getMessage() != null && e.getMessage().contains("InvalidCollection")) {
                String cacheKey = COLLECTION_ID_CACHE_PREFIX + props.getChroma().getCollection();
                try {
                    redisTemplate.delete(cacheKey);
                    log.info("集合不存在，已清除缓存: {}", cacheKey);
                } catch (Exception ex) {
                    log.warn("清除缓存失败: {}", ex.getMessage());
                }
            }
            log.error("Chroma删除异常: docId={}, error={}", docId, e.getMessage());
            return false;
        }
    }

    /**
     * 获取Collection ID（带Redis缓存）
     */
    private String getCollectionId(String collectionName) {
        String cacheKey = COLLECTION_ID_CACHE_PREFIX + collectionName;

        // 1. 先从Redis获取
        try {
            String cachedId = redisTemplate.opsForValue().get(cacheKey);
            if (cachedId != null && !cachedId.isBlank()) {
                log.debug("从Redis缓存获取Collection ID: {} -> {}", collectionName, cachedId);
                return cachedId;
            }
        } catch (Exception e) {
            log.warn("从Redis获取Collection ID失败: {}", e.getMessage());
        }

        // 2. Redis没有，从ChromaDB获取
        try {
            String chromaUrl = props.getChroma().getUrl();
            String getUrl = chromaUrl + "/api/v1/collections/" + collectionName;

            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.getForEntity(getUrl, String.class);

            if (response.getStatusCode() != HttpStatus.OK) {
                log.warn("获取Collection失败: status={}, body={}", response.getStatusCode(), response.getBody());
                return null;
            }

            String collectionId = extractCollectionId(response.getBody());
            if (collectionId != null && !collectionId.isBlank()) {
                // 3. 存入Redis，设置TTL
                try {
                    redisTemplate.opsForValue().set(cacheKey, collectionId, COLLECTION_ID_CACHE_TTL_HOURS, TimeUnit.HOURS);
                    log.info("Collection ID已缓存到Redis: {} -> {}", collectionName, collectionId);
                } catch (Exception e) {
                    log.warn("缓存Collection ID到Redis失败: {}", e.getMessage());
                }
                return collectionId;
            }

            return null;
        } catch (Exception e) {
            log.error("从ChromaDB获取Collection ID失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 清除Collection ID的Redis缓存
     */
    public void clearCollectionIdCache(String collectionName) {
        try {
            String cacheKey = COLLECTION_ID_CACHE_PREFIX + collectionName;
            redisTemplate.delete(cacheKey);
            log.info("已清除Collection ID缓存: {}", collectionName);
        } catch (Exception e) {
            log.warn("清除Collection ID缓存失败: {}", e.getMessage());
        }
    }

    private String extractCollectionId(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode idNode = root.get("id");
            if (idNode != null && !idNode.isNull()) {
                return idNode.asText();
            }
            return null;
        } catch (Exception e) {
            log.warn("解析Collection ID失败: {}", e.getMessage());
            return null;
        }
    }

    public boolean hasKnowledgeDocument(String userId, String md5) {
        return documentRepository.existsByUserIdAndMd5(userId, md5);
    }

    /**
     * 重试向量化（异步版本，前端轮询状态）
     */
    public boolean retryVectorization(String docId) {
        Optional<KnowledgeDocument> docOpt = documentRepository.findById(docId);
        if (docOpt.isEmpty()) {
            log.warn("文档不存在: docId={}", docId);
            return false;
        }

        KnowledgeDocument doc = docOpt.get();
        if (!"vector_failed".equals(doc.getStatus())) {
            log.warn("文档状态不是vector_failed: docId={}, status={}", docId, doc.getStatus());
            return false;
        }

        // 获取文档的 chunks
        List<KnowledgeDocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(docId);
        List<RagChunk> contentList = chunks.stream()
                .sorted(Comparator.comparingInt(KnowledgeDocumentChunk::getChunkIndex))
                .map(this::toRagChunk)
                .collect(Collectors.toList());

        // 更新状态为 processing
        doc.setStatus("processing");
        documentRepository.save(doc);

        // 先清理旧的 ChromaDB 数据
        deleteFromChromaByDocId(docId);

        // 通过 DocumentTaskExecutor 异步写入 ChromaDB（真正的 @Async 跨 Bean 调用）
        documentTaskExecutor.writeToChromaAsync(doc.getUserId(), doc.getFilename(), doc.getMd5(),
                doc.getId(), contentList, null);

        log.info("重试向量化任务已提交: docId={}", docId);
        return true;
    }

    public List<Map<String, Object>> searchKnowledge(String userId, String query, int topK) {
        return searchKnowledge(userId, query, topK, Set.of());
    }

    public List<Map<String, Object>> searchKnowledge(String userId, String query, int topK,
                                                     Set<String> docIdentifiers) {
        Embedding queryEmbedding = embed(query);
        Set<String> selectedIdentifiers = normalizeIdentifiers(docIdentifiers);

        if (chromaAvailable) {
            try {
                return searchChroma(knowledgeStore, queryEmbedding, userId, topK,
                        "knowledge_base", buildKnowledgeFilter(selectedIdentifiers));
            } catch (Exception e) {
                if (!selectedIdentifiers.isEmpty()) {
                    log.warn("Selected knowledge Chroma search failed, fallback to MySQL: selected={}, error={}",
                            selectedIdentifiers, e.getMessage());
                    return searchKnowledgeFromMySQL(userId, queryEmbedding.vector(), topK, selectedIdentifiers);
                }
                throw e;
            }
        } else {
            // 降级：从MySQL读取内容，使用内存计算相似度
            return searchKnowledgeFromMySQL(userId, queryEmbedding.vector(), topK, selectedIdentifiers);
        }
    }

    private List<Map<String, Object>> searchKnowledgeFromMySQL(String userId, float[] queryVector, int topK) {
        return searchKnowledgeFromMySQL(userId, queryVector, topK, Set.of());
    }

    private List<Map<String, Object>> searchKnowledgeFromMySQL(String userId, float[] queryVector, int topK,
                                                               Set<String> docIdentifiers) {
        List<KnowledgeDocument> documents = documentRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> selectedIdentifiers = normalizeIdentifiers(docIdentifiers);

        for (KnowledgeDocument doc : documents) {
            if (!selectedIdentifiers.isEmpty() && !matchesDocumentIdentifiers(doc, selectedIdentifiers)) {
                continue;
            }
            List<KnowledgeDocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(doc.getId());
            for (KnowledgeDocumentChunk chunk : chunks) {
                String retrievalText = chunk.getRetrievalText() != null ? chunk.getRetrievalText() : chunk.getContent();
                Embedding embedding = embed(retrievalText);
                float similarity = cosineSimilarity(queryVector, embedding.vector());

                Map<String, Object> result = new HashMap<>();
                result.put("chunk_id", doc.getMd5() + "_" + chunk.getChunkIndex());
                result.put("user_id", userId);
                result.put("filename", doc.getFilename());
                result.put("md5", doc.getMd5());
                result.put("doc_id", doc.getId());
                result.put("index", chunk.getChunkIndex());
                result.put("content", chunk.getContent());
                result.put("retrieval_text", retrievalText);
                result.put("content_type", chunk.getContentType());
                result.put("section_path", chunk.getSectionPath());
                result.put("parent_id", chunk.getParentId());
                result.put("page_start", chunk.getPageStart());
                result.put("page_end", chunk.getPageEnd());
                result.put("distance", 1.0f - similarity);
                result.put("similarity", similarity);
                result.put("source_type", "knowledge_base");
                results.add(result);
            }
        }

        return results.stream()
                .sorted((a, b) -> Float.compare((float) a.get("distance"), (float) b.get("distance")))
                .limit(topK)
                .collect(Collectors.toList());
    }

    // ========== 文档查询（基于MySQL） ==========

    public List<Map<String, Object>> getUserDocuments(String userId) {
        List<KnowledgeDocument> documents = documentRepository.findByUserIdOrderByCreatedAtDesc(userId);

        // 同时检查 MD5 store，补充可能遗漏的文档
        List<Map<String, String>> md5Records = md5Store.getUserRecords(userId);
        Set<String> existingMd5s = documents.stream()
                .map(KnowledgeDocument::getMd5)
                .collect(java.util.stream.Collectors.toSet());

        List<Map<String, Object>> result = new ArrayList<>(documents.stream().map(doc -> {
            Map<String, Object> docMap = new HashMap<>();
            docMap.put("id", doc.getId());
            docMap.put("md5", doc.getMd5());
            docMap.put("filename", doc.getFilename());
            docMap.put("originalFilename", doc.getOriginalFilename());
            docMap.put("userId", doc.getUserId());
            docMap.put("chunkCount", doc.getChunkCount());
            docMap.put("preview", doc.getPreview());
            docMap.put("fileSize", doc.getFileSize());
            docMap.put("status", doc.getStatus());
            docMap.put("createdAt", doc.getCreatedAt());
            return docMap;
        }).toList());

        // 补充 MD5 store 中存在但 MySQL 中不存在的文档
        for (Map<String, String> md5Record : md5Records) {
            String md5 = md5Record.get("md5");
            if (!existingMd5s.contains(md5)) {
                Map<String, Object> docMap = new HashMap<>();
                docMap.put("id", md5);
                docMap.put("md5", md5);
                docMap.put("filename", md5Record.get("filename"));
                docMap.put("originalFilename", md5Record.get("original_filename"));
                docMap.put("userId", userId);
                docMap.put("chunkCount", 0);
                docMap.put("preview", null);
                docMap.put("fileSize", 0L);
                docMap.put("status", "unknown");
                docMap.put("createdAt", null);
                result.add(docMap);
            }
        }

        return result;
    }

    public Map<String, Object> getDocumentDetail(String userId, String filename) {
        Optional<KnowledgeDocument> docOpt = documentRepository.findByUserIdAndFilename(userId, filename);
        if (docOpt.isEmpty()) {
            return null;
        }

        KnowledgeDocument doc = docOpt.get();
        List<KnowledgeDocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(doc.getId());

        StringBuilder content = new StringBuilder();
        List<Map<String, Object>> chunkDetails = new ArrayList<>();
        for (KnowledgeDocumentChunk chunk : chunks) {
            content.append(chunk.getContent());
            Map<String, Object> chunkDetail = new HashMap<>();
            chunkDetail.put("chunk_id", doc.getMd5() + "_" + chunk.getChunkIndex());
            chunkDetail.put("index", chunk.getChunkIndex());
            chunkDetail.put("content", chunk.getContent());
            chunkDetail.put("content_type", chunk.getContentType());
            chunkDetail.put("section_path", chunk.getSectionPath());
            chunkDetail.put("parent_id", chunk.getParentId());
            chunkDetail.put("page_start", chunk.getPageStart());
            chunkDetail.put("page_end", chunk.getPageEnd());
            chunkDetails.add(chunkDetail);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("id", doc.getId());
        result.put("filename", doc.getFilename());
        result.put("originalFilename", doc.getOriginalFilename());
        result.put("userId", doc.getUserId());
        result.put("md5", doc.getMd5());
        result.put("chunkCount", doc.getChunkCount());
        result.put("content", content.toString());
        result.put("chunks", chunkDetails);
        result.put("images", List.of());
        result.put("fileSize", doc.getFileSize());
        result.put("status", doc.getStatus());
        result.put("createdAt", doc.getCreatedAt());
        return result;
    }

    public Map<String, Object> getDocumentChunks(String userId, String filename) {
        Optional<KnowledgeDocument> docOpt = documentRepository.findByUserIdAndFilename(userId, filename);
        if (docOpt.isEmpty()) {
            return Map.of("filename", filename, "total_chunks", 0, "chunks", List.of());
        }

        KnowledgeDocument doc = docOpt.get();
        List<KnowledgeDocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(doc.getId());

        List<Map<String, Object>> chunkList = chunks.stream().map(chunk -> {
            Map<String, Object> chunkMap = new HashMap<>();
            chunkMap.put("chunk_id", doc.getMd5() + "_" + chunk.getChunkIndex());
            chunkMap.put("index", chunk.getChunkIndex());
            chunkMap.put("content", chunk.getContent());
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("content_type", chunk.getContentType());
            metadata.put("section_path", chunk.getSectionPath());
            metadata.put("parent_id", chunk.getParentId());
            metadata.put("page_start", chunk.getPageStart());
            metadata.put("page_end", chunk.getPageEnd());
            chunkMap.put("metadata", metadata);
            chunkMap.put("images", List.of());
            return chunkMap;
        }).toList();

        return Map.of("filename", filename, "total_chunks", chunks.size(), "chunks", chunkList);
    }

    public List<Map<String, Object>> expandRetrievedContexts(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }

        Map<String, Long> sectionHitCounts = results.stream()
                .collect(Collectors.groupingBy(this::sectionGroupKey, Collectors.counting()));

        List<Map<String, Object>> expanded = new ArrayList<>();
        Set<String> seenContexts = new HashSet<>();
        for (Map<String, Object> result : results) {
            String sourceType = String.valueOf(result.getOrDefault("source_type", result.get("source")));
            Map<String, Object> context = "note".equals(sourceType)
                    ? expandNoteContext(result, sectionHitCounts)
                    : expandKnowledgeContext(result, sectionHitCounts);
            String key = String.valueOf(context.getOrDefault("context_key",
                    context.getOrDefault("chunk_id", UUID.randomUUID().toString())));
            if (seenContexts.add(key)) {
                expanded.add(context);
            }
        }
        return expanded;
    }

    private Map<String, Object> expandKnowledgeContext(Map<String, Object> result,
                                                       Map<String, Long> sectionHitCounts) {
        Map<String, Object> expanded = new HashMap<>(result);
        String docId = stringValue(result.get("doc_id"));
        String parentId = stringValue(result.get("parent_id"));
        String sectionPath = stringValue(result.get("section_path"));
        int hitIndex = intValue(result.get("index"), -1);

        List<KnowledgeDocumentChunk> contextChunks = List.of();
        String contextLevel = "child";
        if (docId != null && sectionPath != null
                && sectionHitCounts.getOrDefault(sectionGroupKey(result), 0L) > 1) {
            contextChunks = chunkRepository.findByDocumentIdAndSectionPath(docId, sectionPath);
            contextLevel = "section";
        }
        if (contextChunks.isEmpty() && docId != null && parentId != null) {
            contextChunks = chunkRepository.findByDocumentIdAndParentId(docId, parentId);
            contextLevel = "parent";
        }
        if (contextChunks.isEmpty() && docId != null && hitIndex >= 0) {
            List<KnowledgeDocumentChunk> all = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(docId);
            contextChunks = all.stream()
                    .filter(c -> Math.abs(c.getChunkIndex() - hitIndex) <= 1)
                    .toList();
            contextLevel = "neighbor";
        }

        if (!contextChunks.isEmpty()) {
            expanded.put("original_content", result.get("content"));
            expanded.put("content", joinKnowledgeChunks(contextChunks, 6000));
            expanded.put("context_level", contextLevel);
            expanded.put("expanded_chunk_count", contextChunks.size());
            expanded.put("context_key", "knowledge:" + docId + ":" + contextLevel + ":"
                    + ("section".equals(contextLevel) ? sectionPath : parentId));
        }
        return expanded;
    }

    private Map<String, Object> expandNoteContext(Map<String, Object> result,
                                                  Map<String, Long> sectionHitCounts) {
        Map<String, Object> expanded = new HashMap<>(result);
        String noteId = stringValue(result.get("note_id"));
        String sectionPath = stringValue(result.get("section_path"));
        int hitIndex = intValue(result.get("index"), -1);

        List<NoteChunk> contextChunks = List.of();
        String contextLevel = "neighbor";
        if (noteId != null && sectionPath != null
                && sectionHitCounts.getOrDefault(sectionGroupKey(result), 0L) > 1) {
            contextChunks = noteChunkRepository.findByNoteIdAndSectionPath(noteId, sectionPath);
            contextLevel = "section";
        }
        if (contextChunks.isEmpty() && noteId != null && hitIndex >= 0) {
            contextChunks = noteChunkRepository.findNeighborhood(noteId,
                    Math.max(0, hitIndex - 1), hitIndex + 1);
        }

        if (!contextChunks.isEmpty()) {
            expanded.put("original_content", result.get("content"));
            expanded.put("content", joinNoteChunks(contextChunks, 5000));
            expanded.put("context_level", contextLevel);
            expanded.put("expanded_chunk_count", contextChunks.size());
            expanded.put("context_key", "note:" + noteId + ":" + contextLevel + ":"
                    + ("section".equals(contextLevel) ? sectionPath : hitIndex));
        }
        return expanded;
    }

    private RagChunk toRagChunk(KnowledgeDocumentChunk chunk) {
        RagChunk ragChunk = new RagChunk();
        ragChunk.setChunkIndex(chunk.getChunkIndex());
        ragChunk.setContent(chunk.getContent());
        ragChunk.setRetrievalText(chunk.getRetrievalText() != null ? chunk.getRetrievalText() : chunk.getContent());
        ragChunk.setContentType(chunk.getContentType());
        ragChunk.setSectionPath(chunk.getSectionPath());
        ragChunk.setParentId(chunk.getParentId());
        ragChunk.setPageStart(chunk.getPageStart());
        ragChunk.setPageEnd(chunk.getPageEnd());
        return ragChunk;
    }

    private String joinKnowledgeChunks(List<KnowledgeDocumentChunk> chunks, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeDocumentChunk chunk : chunks) {
            appendWithLimit(sb, chunk.getContent(), maxChars);
            if (sb.length() >= maxChars) break;
        }
        return sb.toString();
    }

    private String joinNoteChunks(List<NoteChunk> chunks, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (NoteChunk chunk : chunks) {
            appendWithLimit(sb, chunk.getContent(), maxChars);
            if (sb.length() >= maxChars) break;
        }
        return sb.toString();
    }

    private void appendWithLimit(StringBuilder sb, String content, int maxChars) {
        if (content == null || content.isBlank() || sb.length() >= maxChars) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        int remaining = maxChars - sb.length();
        sb.append(content, 0, Math.min(content.length(), remaining));
    }

    private String sectionGroupKey(Map<String, Object> result) {
        String sourceType = String.valueOf(result.getOrDefault("source_type", result.get("source")));
        String owner = "note".equals(sourceType) ? stringValue(result.get("note_id")) : stringValue(result.get("doc_id"));
        String section = stringValue(result.get("section_path"));
        return sourceType + ":" + owner + ":" + section;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String str = String.valueOf(value);
        return str.isBlank() || "null".equalsIgnoreCase(str) ? null : str;
    }

    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    // ========== 检索工具方法 ==========

    /** ChromaDB 向量检索 */
    private Set<String> normalizeIdentifiers(Set<String> identifiers) {
        if (identifiers == null || identifiers.isEmpty()) {
            return Set.of();
        }
        return identifiers.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean matchesDocumentIdentifiers(KnowledgeDocument doc, Set<String> identifiers) {
        if (doc == null || identifiers == null || identifiers.isEmpty()) {
            return true;
        }
        return matchesAnyIdentifier(identifiers,
                doc.getId(), doc.getMd5(), doc.getFilename(), doc.getOriginalFilename());
    }

    private boolean matchesAnyIdentifier(Set<String> identifiers, Object... values) {
        for (Object value : values) {
            String str = stringValue(value);
            if (str != null && identifiers.contains(str)) {
                return true;
            }
        }
        return false;
    }

    private Filter buildKnowledgeFilter(Set<String> identifiers) {
        Set<String> selected = normalizeIdentifiers(identifiers);
        if (selected.isEmpty()) {
            return null;
        }
        return combineOr(
                new IsIn("doc_id", selected),
                new IsIn("md5", selected),
                new IsIn("filename", selected),
                new IsIn("original_filename", selected));
    }

    private Filter combineUserFilter(String userId, Filter extraFilter) {
        Filter userFilter = new IsEqualTo("user_id", userId);
        return extraFilter == null ? userFilter : new And(userFilter, extraFilter);
    }

    private Filter combineOr(Filter first, Filter... rest) {
        Filter result = first;
        for (Filter filter : rest) {
            result = new Or(result, filter);
        }
        return result;
    }

    private List<Map<String, Object>> searchChroma(EmbeddingStore<TextSegment> store,
                                                    Embedding queryEmbedding, String userId,
                                                    int topK, String sourceType) {
        return searchChroma(store, queryEmbedding, userId, topK, sourceType, null);
    }

    private List<Map<String, Object>> searchChroma(EmbeddingStore<TextSegment> store,
                                                    Embedding queryEmbedding, String userId,
                                                    int topK, String sourceType,
                                                    Filter extraFilter) {
        Filter filter = combineUserFilter(userId, extraFilter);
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

    // ========== 工具方法 ==========

    /**
     * 文本向量化（降级兜底）
     */
    /**
     * 批量 Embedding（减少 API 调用次数，供笔记向量化使用）
     */
    private List<Embedding> batchEmbed(List<String> texts) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= EMBEDDING_RETRIES; attempt++) {
            try {
                List<TextSegment> segments = texts.stream()
                        .map(TextSegment::from)
                        .collect(Collectors.toList());
                Response<List<Embedding>> response = embeddingModel.embedAll(segments);
                List<Embedding> embeddings = response.content();
                if (embeddings == null || embeddings.size() != texts.size()) {
                    throw new IllegalStateException("Embedding response size mismatch: expected "
                            + texts.size() + ", actual " + (embeddings == null ? 0 : embeddings.size()));
                }
                return embeddings;
            } catch (Exception e) {
                lastException = asRuntimeException(e);
                log.warn("Embedding batch failed, retrying: batchSize={}, attempt={}/{}, error={}",
                        texts.size(), attempt, EMBEDDING_RETRIES, e.getMessage());
                sleepBeforeEmbeddingRetry(attempt);
            }
        }

        log.warn("Embedding batch failed after {} attempts, falling back to single requests: batchSize={}, error={}",
                EMBEDDING_RETRIES, texts.size(), lastException == null ? "" : lastException.getMessage());
        return texts.stream()
                .map(this::embedWithRetry)
                .collect(Collectors.toList());
    }

    private Embedding embed(String text) {
        return embedWithRetry(text);
    }

    private Embedding embedWithRetry(String text) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= EMBEDDING_RETRIES; attempt++) {
            try {
                Response<Embedding> response = embeddingModel.embed(TextSegment.from(text));
                Embedding embedding = response.content();
                if (embedding == null) {
                    throw new IllegalStateException("Embedding response is empty");
                }
                return embedding;
            } catch (Exception e) {
                lastException = asRuntimeException(e);
                log.warn("Embedding text failed, retrying: chars={}, attempt={}/{}, error={}",
                        text == null ? 0 : text.length(), attempt, EMBEDDING_RETRIES, e.getMessage());
                sleepBeforeEmbeddingRetry(attempt);
            }
        }
        throw lastException != null
                ? lastException
                : new IllegalStateException("Embedding failed without exception");
    }

    private RuntimeException asRuntimeException(Exception e) {
        return e instanceof RuntimeException runtimeException ? runtimeException : new RuntimeException(e);
    }

    private void sleepBeforeEmbeddingRetry(int attempt) {
        try {
            Thread.sleep(EMBEDDING_RETRY_BASE_DELAY_MS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry embedding", e);
        }
    }

    private String buildNoteText(Note note) {
        StringBuilder sb = new StringBuilder();
        if (note.getTitle() != null && !note.getTitle().isEmpty()) sb.append(note.getTitle()).append("\n");
        if (note.getContent() != null) {
            sb.append(note.getContent());
        }
        return sb.toString();
    }

    /**
     * 文本切片（与 DocumentProcessor 逻辑一致）
     */
    private List<String> splitText(String text, int chunkSize, int chunkOverlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;
        chunks = TextChunker.split(text, chunkSize, chunkOverlap);
        if (!chunks.isEmpty()) {
            return chunks;
        }

        String[] separators = {"\n\n", "\n", "。", "！", "？", ".", "!", "?", "；", ";", "，", ","};
        List<String> sentences = splitBySeparators(text, separators);

        StringBuilder currentChunk = new StringBuilder();
        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > chunkSize && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString().trim());
                String overlap = currentChunk.toString();
                int overlapStart = Math.max(0, overlap.length() - chunkOverlap);
                currentChunk = new StringBuilder(overlap.substring(overlapStart));
            }
            currentChunk.append(sentence);
        }
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private List<String> splitBySeparators(String text, String[] separators) {
        List<String> result = new ArrayList<>();
        result.add(text);

        for (String sep : separators) {
            List<String> newResult = new ArrayList<>();
            for (String segment : result) {
                String[] parts = segment.split("(?=" + java.util.regex.Pattern.quote(sep) + ")");
                for (String part : parts) {
                    if (!part.isEmpty()) {
                        newResult.add(part);
                    }
                }
            }
            result = newResult;
            if (result.size() > 1) break;
        }

        return result;
    }

    private float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0f;
        float dot = 0f, normA = 0f, normB = 0f;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i]; }
        if (normA == 0f || normB == 0f) return 0f;
        return dot / (float) (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
