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
        SseEmitter emitter = new SseEmitter(300000L);

        CompletableFuture.runAsync(() -> {
            try {
                for (MultipartFile file : files) {
                    processUploadedFile(userId, file, (stage, data) -> {
                        try {
                            emitter.send(SseEmitter.event()
                                    .data(Map.of("stage", stage, "file", file.getOriginalFilename(), "data", data.toString())));
                        } catch (IOException e) {
                            log.warn("Failed to send SSE event: {}", e.getMessage());
                        }
                    });
                }
                emitter.send(SseEmitter.event().data(Map.of("stage", "all_completed")));
                emitter.complete();
            } catch (Exception e) {
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
                .map(d -> new KnowledgeDocument(
                        (String) d.get("md5"),
                        (String) d.get("filename"),
                        (String) d.get("original_filename"),
                        (String) d.get("user_id"),
                        (int) d.get("chunk_count"),
                        (String) d.get("preview"),
                        null
                ))
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
}
