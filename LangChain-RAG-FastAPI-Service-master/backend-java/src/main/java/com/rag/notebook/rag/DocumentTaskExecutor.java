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
                                                       String docId, List<String> chunks,
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
                                    String docId, List<String> chunks,
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
                String content = chunks.get(i);

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

            int batchSize = 10;
            int totalSegments = segments.size();
            int totalBatches = (int) Math.ceil((double) totalSegments / batchSize);
            int processedCount = 0;

            for (int batchIndex = 0; batchIndex < totalBatches; batchIndex++) {
                int start = batchIndex * batchSize;
                int end = Math.min(start + batchSize, totalSegments);

                List<TextSegment> batchSegments = segments.subList(start, end);
                List<String> batchContents = validContents.subList(start, end);

                List<Embedding> batchEmbeddings = batchEmbed(batchContents);
                knowledgeStore.addAll(batchEmbeddings, batchSegments);

                processedCount += batchSegments.size();

                if (progressCallback != null) {
                    int progress = (int) ((double) processedCount / totalSegments * 100);
                    progressCallback.accept("processing",
                            String.format("向量化进度: %d/%d (%d%%)", processedCount, totalSegments, progress));
                }

                log.info("ChromaDB批量写入进度: docId={}, {}/{} chunks", docId, processedCount, totalSegments);

                if (batchIndex < totalBatches - 1) {
                    Thread.sleep(100);
                }
            }

            log.info("ChromaDB写入成功: docId={}, chunks={}", docId, totalSegments);
            markVectorCompleted(docId);

            if (progressCallback != null) {
                progressCallback.accept("completed", filename);
            }

        } catch (Exception e) {
            log.error("ChromaDB写入失败，标记为vector_failed: docId={}, error={}", docId, e.getMessage(), e);
            markVectorFailed(docId);
            if (progressCallback != null) {
                progressCallback.accept("error", filename + ": " + e.getMessage());
            }
            throw new RuntimeException("ChromaDB写入失败: " + e.getMessage(), e);
        }
    }

    private void markVectorFailed(String docId) {
        documentRepository.findById(docId).ifPresent(doc -> {
            doc.setStatus("vector_failed");
            documentRepository.save(doc);
            log.info("文档状态已更新为vector_failed: docId={}", docId);
        });
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
        try {
            List<TextSegment> segments = texts.stream()
                    .map(TextSegment::from)
                    .toList();
            Response<List<Embedding>> response = embeddingModel.embedAll(segments);
            return response.content();
        } catch (Exception e) {
            log.warn("批量向量化失败，降级为逐个处理: {}", e.getMessage());
            return texts.stream()
                    .map(this::embed)
                    .toList();
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
}
