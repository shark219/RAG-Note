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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VectorStoreService {

    private static final String COLLECTION_ID_CACHE_PREFIX = "chroma:collection:id:";
    private static final long COLLECTION_ID_CACHE_TTL_HOURS = 24;

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
                              NoteChunkRepository noteChunkRepository) {
        this.props = props;
        this.embeddingModel = modelFactory.createEmbeddingModel();
        this.bm25Service = bm25Service;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.cleanupTaskRepository = cleanupTaskRepository;
        this.redisTemplate = redisTemplate;
        this.md5Store = md5Store;
        this.noteChunkRepository = noteChunkRepository;

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

    /**
     * 添加笔记向量（与知识库流程一致：切片→MySQL+ChromaDB+BM25）
     */
    @Transactional
    public void addNoteVector(Note note) {
        String text = buildNoteText(note);
        String title = note.getTitle() != null ? note.getTitle() : "";

        // 1. 文本切片
        List<String> chunks = splitText(text, props.getChroma().getChunkSize(), props.getChroma().getChunkOverlap());
        log.info("笔记切片完成: noteId={}, chunks={}", note.getId(), chunks.size());

        // 2. 写入 MySQL（NoteChunk 表）
        List<NoteChunk> chunkEntities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            NoteChunk chunk = new NoteChunk();
            chunk.setId(UUID.randomUUID().toString());
            chunk.setNoteId(note.getId());
            chunk.setChunkIndex(i);
            chunk.setContent(chunks.get(i));
            chunkEntities.add(chunk);
        }
        noteChunkRepository.saveAll(chunkEntities);

        // 3. 写入 BM25 索引（不依赖 ChromaDB）
        for (int i = 0; i < chunks.size(); i++) {
            String chunkKey = note.getId() + "_" + i;
            bm25Service.addDocument(note.getUserId(), chunkKey, chunks.get(i),
                    Map.of("source", "note", "chunk_id", chunkKey,
                            "note_id", note.getId(), "title", title, "user_id", note.getUserId()));
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
    public void writeNoteToChromaAsync(String noteId, String userId, String title, List<String> chunks) {
        try {
            // 1. 清理旧数据
            deleteNoteChunksFromChroma(noteId);

            // 2. 批量写入新数据
            List<TextSegment> segments = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                String chunkKey = noteId + "_" + i;
                String content = chunks.get(i);

                Map<String, Object> meta = new HashMap<>();
                meta.put("chunk_id", chunkKey);
                meta.put("note_id", noteId);
                meta.put("user_id", userId);
                meta.put("title", title);
                meta.put("content", content);
                meta.put("index", i);
                meta.put("source", "note");

                segments.add(TextSegment.from(content, dev.langchain4j.data.document.Metadata.from(meta)));
            }

            // 分批写入
            int batchSize = 10;
            for (int i = 0; i < segments.size(); i += batchSize) {
                int end = Math.min(i + batchSize, segments.size());
                List<TextSegment> batch = segments.subList(i, end);
                List<String> batchContents = chunks.subList(i, end);
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

    // ========== 知识库向量操作（MySQL + ChromaDB 双写） ==========

    @Transactional
    public void addKnowledgeDocument(String userId, String filename, String md5,
                                     List<String> chunks, Map<String, Object> metadata) {
        String originalFilename = (String) metadata.getOrDefault("original_filename", filename);
        Long fileSize = metadata.containsKey("file_size") ? (Long) metadata.get("file_size") : 0L;
        addKnowledgeDocument(userId, filename, originalFilename, md5, fileSize, chunks, null);
    }

    @Transactional
    public void addKnowledgeDocument(String userId, String filename, String md5,
                                     List<String> chunks, Map<String, Object> metadata,
                                     BiConsumer<String, Object> progressCallback) {
        String originalFilename = (String) metadata.getOrDefault("original_filename", filename);
        Long fileSize = metadata.containsKey("file_size") ? (Long) metadata.get("file_size") : 0L;
        addKnowledgeDocument(userId, filename, originalFilename, md5, fileSize, chunks, progressCallback);
    }

    @Transactional
    public void addKnowledgeDocument(String userId, String filename, String originalFilename,
                                     String md5, Long fileSize, List<String> chunks) {
        addKnowledgeDocument(userId, filename, originalFilename, md5, fileSize, chunks, null);
    }

    @Transactional
    public void addKnowledgeDocument(String userId, String filename, String originalFilename,
                                     String md5, Long fileSize, List<String> chunks,
                                     BiConsumer<String, Object> progressCallback) {
        log.info("开始添加知识文档: userId={}, filename={}, md5={}, chunks={}", userId, filename, md5, chunks.size());

        // 0. MD5去重检查：如果已存在相同MD5的文档，跳过重复写入
        if (documentRepository.existsByUserIdAndMd5(userId, md5)) {
            log.info("文档已存在（MD5重复），跳过写入: userId={}, md5={}, filename={}", userId, md5, filename);
            if (progressCallback != null) {
                progressCallback.accept("skipping", originalFilename);
            }
            return;
        }

        // 1. 先写MySQL（事务保证）
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
        log.info("MySQL文档保存成功: docId={}", document.getId());

        // 2. 写入切片到MySQL
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
        log.info("MySQL切片保存成功: docId={}, chunks={}", document.getId(), chunks.size());

        // 3. 异步写入ChromaDB（写入成功后会更新状态为completed）
        writeToChromaAsync(userId, filename, md5, document.getId(), chunks, false, progressCallback);

        // 4. 写入BM25索引
        for (int i = 0; i < chunks.size(); i++) {
            String key = md5 + "_" + i;
            bm25Service.addDocument(userId, key, chunks.get(i),
                    Map.of("source", "knowledge_base", "chunk_id", key,
                            "filename", filename, "md5", md5, "user_id", userId));
        }

        log.info("知识文档添加完成: docId={}, filename={}, chunks={}", document.getId(), filename, chunks.size());
    }

    @Async
    public void writeToChromaAsync(String userId, String filename, String md5, String docId, List<String> chunks) {
        writeToChromaAsync(userId, filename, md5, docId, chunks, false, null);
    }

    @Async
    public void writeToChromaAsync(String userId, String filename, String md5, String docId,
                                   List<String> chunks, boolean cleanBeforeWrite) {
        writeToChromaAsync(userId, filename, md5, docId, chunks, cleanBeforeWrite, null);
    }

    @Async
    public void writeToChromaAsync(String userId, String filename, String md5, String docId,
                                   List<String> chunks, boolean cleanBeforeWrite,
                                   BiConsumer<String, Object> progressCallback) {
        if (!chromaAvailable) {
            log.warn("ChromaDB不可用，标记为vector_failed: docId={}", docId);
            markVectorFailed(docId);
            return;
        }

        try {
            // 1. 如果是重试场景，先清理旧数据
            if (cleanBeforeWrite) {
                log.info("重试场景，先清理旧数据: docId={}", docId);
                deleteFromChromaByDocId(docId);
            }

            // 2. 批量构建元数据和准备内容
            List<TextSegment> segments = new ArrayList<>();
            List<String> validContents = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                String key = md5 + "_" + i;
                String content = chunks.get(i);

                // 跳过空切片
                if (content == null || content.isBlank()) {
                    log.warn("跳过空切片: key={}", key);
                    continue;
                }

                Map<String, Object> meta = new HashMap<>();
                meta.put("chunk_id", key);
                meta.put("user_id", userId);
                meta.put("filename", filename);
                meta.put("md5", md5);
                meta.put("doc_id", docId);
                meta.put("index", i);
                meta.put("content", content);

                segments.add(TextSegment.from(content, dev.langchain4j.data.document.Metadata.from(meta)));
                validContents.add(content);
            }

            // 3. 分批处理（批量 Embedding + 分批写入 ChromaDB）
            int batchSize = 10;  // 每批处理 10 个 chunks（减小批量避免超时）
            int totalSegments = segments.size();
            int totalBatches = (int) Math.ceil((double) totalSegments / batchSize);
            int processedCount = 0;

            for (int batchIndex = 0; batchIndex < totalBatches; batchIndex++) {
                int start = batchIndex * batchSize;
                int end = Math.min(start + batchSize, totalSegments);

                List<TextSegment> batchSegments = segments.subList(start, end);
                List<String> batchContents = validContents.subList(start, end);

                // 批量 Embedding
                List<Embedding> batchEmbeddings = batchEmbed(batchContents);

                // 分批写入 ChromaDB
                knowledgeStore.addAll(batchEmbeddings, batchSegments);

                processedCount += batchSegments.size();

                // 发送进度回调
                if (progressCallback != null) {
                    int progress = (int) ((double) processedCount / totalSegments * 100);
                    progressCallback.accept("processing",
                            String.format("向量化进度: %d/%d (%d%%)", processedCount, totalSegments, progress));
                }

                log.info("ChromaDB批量写入进度: docId={}, {}/{} chunks", docId, processedCount, totalSegments);

                // 批次之间添加短暂延迟，避免 ChromaDB 过载
                if (batchIndex < totalBatches - 1) {
                    Thread.sleep(100);  // 100ms 延迟
                }
            }

            log.info("ChromaDB写入成功: docId={}, chunks={}", docId, totalSegments);

            // 写入成功后，更新MySQL状态为completed
            markVectorCompleted(docId);
        } catch (Exception e) {
            log.error("ChromaDB写入失败，标记为vector_failed: docId={}, error={}", docId, e.getMessage());
            markVectorFailed(docId);
        }
    }

    /**
     * 标记文档向量化失败
     */
    private void markVectorFailed(String docId) {
        documentRepository.findById(docId).ifPresent(doc -> {
            doc.setStatus("vector_failed");
            documentRepository.save(doc);
            log.info("文档状态已更新为vector_failed: docId={}", docId);
        });
    }

    /**
     * 标记文档向量化完成
     */
    private void markVectorCompleted(String docId) {
        documentRepository.findById(docId).ifPresent(doc -> {
            if (!"completed".equals(doc.getStatus())) {
                doc.setStatus("completed");
                documentRepository.save(doc);
                log.info("文档状态已更新为completed: docId={}", docId);
            }
        });
    }

    /**
     * 从ChromaDB删除指定文档的向量数据
     * 优先删除MySQL，ChromaDB删除失败时记录到清理表
     */
    @Transactional
    public void deleteKnowledgeByFilename(String userId, String filename) {
        // 1. 先查找文档
        Optional<KnowledgeDocument> docOpt = documentRepository.findByUserIdAndFilename(userId, filename);
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
        for (KnowledgeDocumentChunk chunk : chunks) {
            String chunkKey = md5 + "_" + chunk.getChunkIndex();
            bm25Service.deleteDocument(userId, chunkKey);
        }

        // 4. 删除MySQL（无论ChromaDB是否成功）
        chunkRepository.deleteByDocumentId(docId);
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
        for (KnowledgeDocument doc : documents) {
            chunkRepository.deleteByDocumentId(doc.getId());
        }
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
        List<String> contentList = chunks.stream()
                .sorted(Comparator.comparingInt(KnowledgeDocumentChunk::getChunkIndex))
                .map(KnowledgeDocumentChunk::getContent)
                .collect(Collectors.toList());

        // 更新状态为 processing
        doc.setStatus("processing");
        documentRepository.save(doc);

        // 异步写入 ChromaDB
        writeToChromaAsync(doc.getUserId(), doc.getFilename(), doc.getMd5(), doc.getId(), contentList, true);

        log.info("重试向量化任务已提交: docId={}", docId);
        return true;
    }

    public List<Map<String, Object>> searchKnowledge(String userId, String query, int topK) {
        Embedding queryEmbedding = embed(query);

        if (chromaAvailable) {
            return searchChroma(knowledgeStore, queryEmbedding, userId, topK, "knowledge_base");
        } else {
            // 降级：从MySQL读取内容，使用内存计算相似度
            return searchKnowledgeFromMySQL(userId, queryEmbedding.vector(), topK);
        }
    }

    private List<Map<String, Object>> searchKnowledgeFromMySQL(String userId, float[] queryVector, int topK) {
        List<KnowledgeDocument> documents = documentRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<Map<String, Object>> results = new ArrayList<>();

        for (KnowledgeDocument doc : documents) {
            List<KnowledgeDocumentChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(doc.getId());
            for (KnowledgeDocumentChunk chunk : chunks) {
                Embedding embedding = embed(chunk.getContent());
                float similarity = cosineSimilarity(queryVector, embedding.vector());

                Map<String, Object> result = new HashMap<>();
                result.put("chunk_id", doc.getMd5() + "_" + chunk.getChunkIndex());
                result.put("user_id", userId);
                result.put("filename", doc.getFilename());
                result.put("md5", doc.getMd5());
                result.put("index", chunk.getChunkIndex());
                result.put("content", chunk.getContent());
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
            chunkMap.put("metadata", Map.of());
            chunkMap.put("images", List.of());
            return chunkMap;
        }).toList();

        return Map.of("filename", filename, "total_chunks", chunks.size(), "chunks", chunkList);
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

    // ========== 工具方法 ==========

    /**
     * 批量 Embedding（减少 API 调用次数）
     */
    private List<Embedding> batchEmbed(List<String> texts) {
        try {
            List<TextSegment> segments = texts.stream()
                    .map(TextSegment::from)
                    .collect(Collectors.toList());
            Response<List<Embedding>> response = embeddingModel.embedAll(segments);
            return response.content();
        } catch (Exception e) {
            log.warn("批量向量化失败，降级为逐个处理: {}", e.getMessage());
            // 降级为逐个处理
            return texts.stream()
                    .map(this::embed)
                    .collect(Collectors.toList());
        }
    }

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
