package com.rag.notebook.knowledge.service;

import com.rag.notebook.common.exception.BusinessException;
import com.rag.notebook.knowledge.dto.KnowledgeDocument;
import com.rag.notebook.knowledge.dto.KnowledgeListResponse;
import com.rag.notebook.knowledge.dto.MD5ListResponse;
import com.rag.notebook.knowledge.dto.MD5Record;
import com.rag.notebook.rag.DocumentProcessor;
import com.rag.notebook.rag.Md5Store;
import com.rag.notebook.rag.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

@Slf4j
@Service
public class KnowledgeService {

    private final VectorStoreService vectorStoreService;
    private final DocumentProcessor documentProcessor;
    private final Md5Store md5Store;
    private final Executor taskExecutor;

    public KnowledgeService(VectorStoreService vectorStoreService,
                            DocumentProcessor documentProcessor, Md5Store md5Store,
                            @Qualifier("taskExecutor") Executor taskExecutor) {
        this.vectorStoreService = vectorStoreService;
        this.documentProcessor = documentProcessor;
        this.md5Store = md5Store;
        this.taskExecutor = taskExecutor;
    }

    public void uploadSingle(String userId, MultipartFile file) {
        processUploadedFile(userId, file, (stage, data) -> {});
    }

    public void uploadMultiple(String userId, MultipartFile[] files) {
        for (MultipartFile file : files) {
            processUploadedFile(userId, file, (stage, data) -> {});
        }
    }

    public SseEmitter uploadMultipleStream(String userId, MultipartFile[] files) {
        SseEmitter emitter = new SseEmitter(0L);
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        AtomicBoolean connected = new AtomicBoolean(true);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);
        Set<String> skippedFiles = ConcurrentHashMap.newKeySet();

        // 心跳（每15秒，防止网关/代理超时断开连接）
        ScheduledExecutorService heartbeater = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });

        Runnable cleanup = () -> {
            connected.set(false);
            heartbeater.shutdownNow();
        };
        emitter.onCompletion(cleanup);
        emitter.onError(ex -> { log.warn("SSE连接错误: {}", ex.getMessage()); cleanup.run(); });
        emitter.onTimeout(() -> { log.warn("SSE连接超时"); cleanup.run(); });

        heartbeater.scheduleAtFixedRate(() -> {
            if (!connected.get()) { heartbeater.shutdown(); return; }
            try {
                emitter.send(SseEmitter.event().comment("heartbeat").data("{\"event_type\":\"heartbeat\"}"));
            } catch (IOException e) {
                log.warn("心跳发送失败，客户端已断开");
                connected.set(false);
                heartbeater.shutdown();
            }
        }, 15, 15, TimeUnit.SECONDS);

        // 提交所有文件异步处理
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename();
            if (filename == null) {
                failedCount.incrementAndGet();
                continue;
            }

            String finalFilename = filename;
            CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
                try {
                    File tempFile = Files.createTempFile("upload_", "_" + finalFilename).toFile();
                    try {
                        file.transferTo(tempFile);
                        return documentProcessor.processFile(tempFile, finalFilename, userId,
                                (stage, data) -> sendSseEvent(emitter, objectMapper, connected, skippedFiles, finalFilename, stage, data));
                    } finally {
                        tempFile.delete();
                    }
                } catch (Exception e) {
                    log.error("Failed to process file {}: {}", finalFilename, e.getMessage(), e);
                    sendSseEvent(emitter, objectMapper, connected, skippedFiles, finalFilename, "error", e.getMessage());
                    throw new CompletionException(e);
                }
            }, taskExecutor).thenCompose(f -> f);

            // 各文件完成后更新计数
            futures.add(future.whenComplete((res, err) -> {
                if (err != null) {
                    log.error("文件异步处理失败: {}, error: {}", finalFilename, err.getMessage() != null ? err.getMessage() : err.toString());
                    failedCount.incrementAndGet();
                } else if (skippedFiles.contains(finalFilename)) {
                    skippedCount.incrementAndGet();
                } else {
                    successCount.incrementAndGet();
                }
            }));
        }

        // 所有文件都完成后发送 finish 事件
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((v, err) -> {
                    heartbeater.shutdown();
                    if (connected.get()) {
                        try {
                            Map<String, Object> finish = new LinkedHashMap<>();
                            finish.put("event_type", "finish");
                            finish.put("success_count", successCount.get());
                            finish.put("failed_count", failedCount.get());
                            finish.put("skipped_count", skippedCount.get());
                            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(finish)));
                        } catch (Exception e) {
                            log.warn("发送finish事件失败: {}", e.getMessage());
                        }
                        try {
                            emitter.complete();
                        } catch (Exception ignored) {
                        }
                    }
                });

        return emitter;
    }

    private void sendSseEvent(SseEmitter emitter,
                               com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                               AtomicBoolean connected,
                               Set<String> skippedFiles,
                               String filename,
                               String stage,
                               Object data) {
        if (!connected.get()) return;
        try {
            Map<String, Object> eventData = new LinkedHashMap<>();
            eventData.put("event_type", stage);
            eventData.put("filename", filename);
            eventData.put("message", data.toString());

            if ("skipping".equals(stage)) {
                skippedFiles.add(filename);
            }

            String ds = data.toString();
            if (ds.contains("向量化进度:")) {
                int si = ds.lastIndexOf("(");
                int ei = ds.lastIndexOf("%");
                if (si > 0 && ei > si) {
                    try {
                        eventData.put("progress", Integer.parseInt(ds.substring(si + 1, ei)));
                    } catch (NumberFormatException ignored) {}
                }
                eventData.put("event_type", "processing");
            }

            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(eventData)));
        } catch (IOException e) {
            log.warn("SSE发送失败，客户端可能已断开: {}", e.getMessage());
            connected.set(false);
        } catch (Exception e) {
            log.warn("SSE事件发送异常: {}", e.getMessage());
        }
    }

    private CompletableFuture<Void> processUploadedFile(String userId, MultipartFile file,
                                                         BiConsumer<String, Object> progressCallback) {
        try {
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null) {
                progressCallback.accept("error", "文件名不能为空");
                return CompletableFuture.failedFuture(new BusinessException("文件名不能为空"));
            }

            File tempFile = Files.createTempFile("upload_", "_" + originalFilename).toFile();
            try {
                file.transferTo(tempFile);
                return documentProcessor.processFile(tempFile, originalFilename, userId, progressCallback);
            } finally {
                tempFile.delete();
            }
        } catch (Exception e) {
            log.error("Failed to process uploaded file: {}", e.getMessage(), e);
            progressCallback.accept("error", e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }

    public void cleanUserDocuments(String userId) {
        vectorStoreService.deleteUserKnowledge(userId);
        md5Store.deleteByUser(userId);  // 同时删除MD5记录
    }

    public void clearMd5Records(String userId, boolean deleteDocuments) {
        if (deleteDocuments) {
            vectorStoreService.deleteUserKnowledge(userId);
        }
        md5Store.deleteByUser(userId);
    }

    public void deleteMd5Record(String userId, String md5, boolean deleteDocuments) {
        Map<String, String> record = md5Store.getRecord(md5, userId);
        if (record == null) {
            throw new BusinessException("MD5记录不存在");
        }
        if (deleteDocuments) {
            vectorStoreService.deleteKnowledgeByFilename(userId, record.get("filename"));
        }
        md5Store.deleteByMd5(md5, userId);
    }

    public void deleteByFilename(String userId, String filename, boolean deleteDocuments) {
        if (deleteDocuments) {
            vectorStoreService.deleteKnowledgeByFilename(userId, filename);
        }
    }

    public MD5ListResponse listMd5Records(String userId) {
        List<Map<String, String>> records = md5Store.getUserRecords(userId);
        List<MD5Record> md5Records = records.stream()
                .map(r -> new MD5Record(r.get("md5"), r.get("filename"), r.get("original_filename"), null))
                .toList();
        return new MD5ListResponse(md5Records, md5Records.size());
    }

    public MD5Record getMd5Info(String userId, String md5) {
        Map<String, String> record = md5Store.getRecord(md5, userId);
        if (record == null) return null;
        return new MD5Record(record.get("md5"), record.get("filename"), record.get("original_filename"), null);
    }

    public KnowledgeListResponse listDocuments(String userId) {
        List<Map<String, Object>> docs = vectorStoreService.getUserDocuments(userId);

        List<KnowledgeDocument> documents = new ArrayList<>();
        for (Map<String, Object> d : docs) {
            try {
                Object createdAt = d.get("createdAt");
                String createdAtStr = createdAt != null ? createdAt.toString() : null;

                Object chunkCountObj = d.get("chunkCount");
                int chunkCount = 0;
                if (chunkCountObj instanceof Integer) {
                    chunkCount = (Integer) chunkCountObj;
                } else if (chunkCountObj instanceof Long) {
                    chunkCount = ((Long) chunkCountObj).intValue();
                }

                documents.add(new KnowledgeDocument(
                        (String) d.get("id"),
                        (String) d.get("filename"),
                        (String) d.get("originalFilename"),
                        (String) d.get("userId"),
                        (String) d.get("md5"),
                        chunkCount,
                        (String) d.get("preview"),
                        (String) d.get("status"),
                        createdAtStr
                ));
            } catch (Exception e) {
                log.warn("映射文档记录失败: {}, error: {}", d, e.getMessage());
            }
        }

        return new KnowledgeListResponse(documents, documents.size());
    }

    public Map<String, Object> getDocumentDetail(String userId, String filename) {
        Map<String, Object> detail = vectorStoreService.getDocumentDetail(userId, filename);
        if (detail == null) {
            throw new BusinessException("文档不存在");
        }
        return detail;
    }

    public Map<String, Object> getDocumentChunks(String userId, String filename) {
        return vectorStoreService.getDocumentChunks(userId, filename);
    }

    public void retryVectorization(String userId, String docId) {
        boolean success = vectorStoreService.retryVectorization(docId);
        if (!success) {
            throw new BusinessException("重试失败：文档不存在或状态不是vector_failed");
        }
    }
}
