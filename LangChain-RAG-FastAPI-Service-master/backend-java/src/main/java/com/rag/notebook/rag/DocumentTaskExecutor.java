package com.rag.notebook.rag;

import com.rag.notebook.agent.ModelFactory;
import com.rag.notebook.config.ApplicationProperties;
import com.rag.notebook.knowledge.repository.KnowledgeDocumentRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

@Slf4j
@Service
public class DocumentTaskExecutor {

    private static final Duration CHROMA_TIMEOUT = Duration.ofMinutes(2);
    private static final int CHROMA_WRITE_RETRIES = 3;
    private static final int EMBEDDING_BATCH_SIZE = 3;
    private static final int EMBEDDING_RETRIES = 5;
    private static final long EMBEDDING_RETRY_BASE_DELAY_MS = 1000L;

    private final ApplicationProperties props;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeDocumentRepository documentRepository;
    private final Executor documentExecutor;

    private EmbeddingStore<TextSegment> knowledgeStore;
    private boolean chromaAvailable = false;

    public DocumentTaskExecutor(ApplicationProperties props, ModelFactory modelFactory,
                                KnowledgeDocumentRepository documentRepository,
                                @Qualifier("documentExecutor") Executor documentExecutor) {
        this.props = props;
        this.embeddingModel = modelFactory.createEmbeddingModel();
        this.documentRepository = documentRepository;
        this.documentExecutor = documentExecutor;

        try {
            String chromaUrl = props.getChroma().getUrl();
            this.knowledgeStore = ChromaEmbeddingStore.builder()
                    .baseUrl(chromaUrl)
                    .collectionName(props.getChroma().getCollection())
                    .timeout(CHROMA_TIMEOUT)
                    .build();
            this.chromaAvailable = true;
            log.info("DocumentTaskExecutor ChromaDB已连接: {}", chromaUrl);
        } catch (Exception e) {
            log.warn("DocumentTaskExecutor ChromaDB连接失败，跳过向量索引: {}", e.getMessage());
            this.knowledgeStore = null;
        }
    }

    /**
     * 异步写入 ChromaDB，返回的 CompletableFuture 会正确反映执行结果
     */
    public CompletableFuture<Void> writeToChromaAsync(String userId, String filename, String md5,
                                                       String docId, List<RagChunk> chunks,
                                                       BiConsumer<String, Object> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            writeToChromaSync(userId, filename, md5, docId, chunks, progressCallback);
            return null;
        }, documentExecutor);
    }

    /**
     * 同步写入 ChromaDB，异常时直接抛出
     */
    private void writeToChromaSync(String userId, String filename, String md5,
                                    String docId, List<RagChunk> chunks,
                                    BiConsumer<String, Object> progressCallback) {
        if (!chromaAvailable) {
            log.warn("ChromaDB不可用，标记为vector_failed: docId={}", docId);
            markVectorFailed(docId);
            if (progressCallback != null) {
                progressCallback.accept("error", "ChromaDB不可用");
            }
            throw new RuntimeException("ChromaDB不可用");
        }

        try {
            List<TextSegment> segments = new ArrayList<>();
            List<String> validContents = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                String key = md5 + "_" + i;
                RagChunk chunk = chunks.get(i);
                String content = chunk.getContent();
                String retrievalText = chunk.getRetrievalText();

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
                meta.put("retrieval_text", retrievalText);
                meta.put("content_type", chunk.getContentType());
                meta.put("section_path", chunk.getSectionPath());
                if (chunk.getParentId() != null) meta.put("parent_id", chunk.getParentId());
                if (chunk.getPageStart() != null) meta.put("page_start", chunk.getPageStart());
                if (chunk.getPageEnd() != null) meta.put("page_end", chunk.getPageEnd());

                segments.add(TextSegment.from(retrievalText, dev.langchain4j.data.document.Metadata.from(meta)));
                validContents.add(retrievalText);
            }

            int batchSize = EMBEDDING_BATCH_SIZE;
            int totalSegments = segments.size();
            int totalBatches = (int) Math.ceil((double) totalSegments / batchSize);
            int processedCount = 0;

            for (int batchIndex = 0; batchIndex < totalBatches; batchIndex++) {
                int start = batchIndex * batchSize;
                int end = Math.min(start + batchSize, totalSegments);

                List<TextSegment> batchSegments = segments.subList(start, end);
                List<String> batchContents = validContents.subList(start, end);

                List<Embedding> batchEmbeddings = batchEmbed(batchContents);
                addBatchToChromaWithRetry(batchEmbeddings, batchSegments, docId, batchIndex + 1);

                processedCount += batchSegments.size();

                if (progressCallback != null) {
                    int progress = (int) ((double) processedCount / totalSegments * 100);
                    progressCallback.accept("processing",
                            String.format("向量化进度: %d/%d (%d%%)", processedCount, totalSegments, progress));
                }

                log.info("ChromaDB批量写入进度: docId={}, {}/{} chunks", docId, processedCount, totalSegments);

                if (batchIndex < totalBatches - 1) {
                    Thread.sleep(300);
                }
            }

            log.info("ChromaDB写入成功: docId={}, chunks={}", docId, totalSegments);
            markVectorCompleted(docId);

            if (progressCallback != null) {
                progressCallback.accept("completed", filename);
            }

        } catch (Exception e) {
            log.error("向量化或ChromaDB写入失败，标记为vector_failed: docId={}, error={}", docId, e.getMessage(), e);
            markVectorFailed(docId);
            if (progressCallback != null) {
                progressCallback.accept("error", filename + ": 向量化或ChromaDB写入失败: " + e.getMessage());
            }
            throw new RuntimeException("向量化或ChromaDB写入失败: " + e.getMessage(), e);
        }
    }

    private void markVectorFailed(String docId) {
        documentRepository.findById(docId).ifPresent(doc -> {
            doc.setStatus("vector_failed");
            documentRepository.save(doc);
            log.info("文档状态已更新为vector_failed: docId={}", docId);
        });
    }

    private void addBatchToChromaWithRetry(List<Embedding> batchEmbeddings,
                                           List<TextSegment> batchSegments,
                                           String docId,
                                           int batchNumber) throws InterruptedException {
        for (int attempt = 1; attempt <= CHROMA_WRITE_RETRIES; attempt++) {
            try {
                knowledgeStore.addAll(batchEmbeddings, batchSegments);
                return;
            } catch (RuntimeException e) {
                if (attempt == CHROMA_WRITE_RETRIES) {
                    throw e;
                }
                log.warn("ChromaDB batch write failed, retrying: docId={}, batch={}, attempt={}, error={}",
                        docId, batchNumber, attempt, e.getMessage());
                Thread.sleep(1000L * attempt);
            }
        }
    }

    private void markVectorCompleted(String docId) {
        documentRepository.findById(docId).ifPresent(doc -> {
            if (!"completed".equals(doc.getStatus())) {
                doc.setStatus("completed");
                documentRepository.save(doc);
                log.info("文档状态已更新为completed: docId={}", docId);
            }
        });
    }

    private List<Embedding> batchEmbed(List<String> texts) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= EMBEDDING_RETRIES; attempt++) {
            try {
                List<TextSegment> segments = texts.stream()
                        .map(TextSegment::from)
                        .toList();
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
                .toList();
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
}
