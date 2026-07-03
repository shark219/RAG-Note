package com.rag.notebook.knowledge.service;

import com.rag.notebook.common.exception.BusinessException;
import com.rag.notebook.knowledge.dto.*;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
        SseEmitter emitter = new SseEmitter(600000L);  // 10 分钟超时
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

        CompletableFuture.runAsync(() -> {
            int successCount = 0;
            int failedCount = 0;

            try {
                for (MultipartFile file : files) {
                    processUploadedFile(userId, file, (stage, data) -> {
                        try {
                            // 构建前端期望的事件格式
                            Map<String, Object> eventData = new java.util.LinkedHashMap<>();
                            eventData.put("event_type", stage);
                            eventData.put("filename", file.getOriginalFilename());
                            eventData.put("message", data.toString());

                            // 解析进度信息
                            String dataStr = data.toString();
                            if (dataStr.contains("向量化进度:")) {
                                // 提取百分比
                                int percentStart = dataStr.lastIndexOf("(");
                                int percentEnd = dataStr.lastIndexOf("%");
                                if (percentStart > 0 && percentEnd > percentStart) {
                                    String percent = dataStr.substring(percentStart + 1, percentEnd);
                                    try {
                                        eventData.put("progress", Integer.parseInt(percent));
                                    } catch (NumberFormatException ignored) {}
                                }
                                eventData.put("event_type", "processing");
                            }

                            String json = objectMapper.writeValueAsString(eventData);
                            emitter.send(SseEmitter.event().data(json));
                        } catch (Exception e) {
                            log.warn("Failed to send SSE event: {}", e.getMessage());
                        }
                    });

                    successCount++;
                }

                // 发送完成事件
                Map<String, Object> finishEvent = new java.util.LinkedHashMap<>();
                finishEvent.put("event_type", "finish");
                finishEvent.put("success_count", successCount);
                finishEvent.put("failed_count", failedCount);
                emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(finishEvent)));
                emitter.complete();
            } catch (Exception e) {
                failedCount++;
                try {
                    Map<String, Object> finishEvent = new java.util.LinkedHashMap<>();
                    finishEvent.put("event_type", "finish");
                    finishEvent.put("success_count", successCount);
                    finishEvent.put("failed_count", failedCount);
                    emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(finishEvent)));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        }, taskExecutor);

        return emitter;
    }

    private void processUploadedFile(String userId, MultipartFile file,
                                     BiConsumer<String, Object> progressCallback) {
        try {
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null) {
                throw new BusinessException("文件名不能为空");
            }

            // Write to temp file
            File tempFile = Files.createTempFile("upload_", "_" + originalFilename).toFile();
            try {
                file.transferTo(tempFile);
                documentProcessor.processFile(tempFile, originalFilename, userId, progressCallback);
            } finally {
                tempFile.delete();
            }
        } catch (Exception e) {
            log.error("Failed to process uploaded file: {}", e.getMessage(), e);
            progressCallback.accept("error", e.getMessage());
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
        List<KnowledgeDocument> documents = docs.stream()
                .map(d -> {
                    Object createdAt = d.get("createdAt");
                    String createdAtStr = createdAt != null ? createdAt.toString() : null;
                    return new KnowledgeDocument(
                            (String) d.get("id"),
                            (String) d.get("filename"),
                            (String) d.get("originalFilename"),
                            (String) d.get("userId"),
                            (int) d.get("chunkCount"),
                            (String) d.get("preview"),
                            (String) d.get("status"),
                            createdAtStr
                    );
                })
                .toList();
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
