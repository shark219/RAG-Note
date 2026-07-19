package com.rag.notebook.knowledge.controller;

import com.rag.notebook.common.auth.UserId;
import com.rag.notebook.common.result.ApiResponse;
import com.rag.notebook.knowledge.dto.*;
import com.rag.notebook.knowledge.service.KnowledgeService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping("/add/single")
    public ApiResponse<Void> uploadSingle(@UserId String userId, @RequestParam("file") MultipartFile file) {
        knowledgeService.uploadSingle(userId, file);
        return ApiResponse.success("文件已成功上传并存储到向量数据库");
    }

    @PostMapping("/add/multiple")
    public ApiResponse<Void> uploadMultiple(@UserId String userId, @RequestParam("files") MultipartFile[] files) {
        knowledgeService.uploadMultiple(userId, files);
        return ApiResponse.success("文件已成功上传并存储到向量数据库");
    }

    @PostMapping("/add/multiple/stream")
    public SseEmitter uploadMultipleStream(@UserId String userId, @RequestParam("files") MultipartFile[] files) {
        return knowledgeService.uploadMultipleStream(userId, files);
    }

    @DeleteMapping("/clean")
    public ApiResponse<Void> cleanUserDocuments(@UserId String userId) {
        knowledgeService.cleanUserDocuments(userId);
        return ApiResponse.success("已成功删除用户上传的所有向量");
    }

    @DeleteMapping("/md5/clear")
    public ApiResponse<Void> clearMd5Records(
            @UserId String userId,
            @RequestParam(defaultValue = "true") boolean deleteDocuments) {
        knowledgeService.clearMd5Records(userId, deleteDocuments);
        return ApiResponse.success("MD5记录已清除");
    }

    @DeleteMapping("/md5/delete/{md5}")
    public ApiResponse<Void> deleteMd5Record(
            @UserId String userId, @PathVariable String md5,
            @RequestParam(defaultValue = "true") boolean deleteDocuments) {
        knowledgeService.deleteMd5Record(userId, md5, deleteDocuments);
        return ApiResponse.success("MD5记录已删除");
    }

    @DeleteMapping("/delete/filename")
    public ApiResponse<Void> deleteByFilename(
            @UserId String userId, @RequestParam String filename,
            @RequestParam(defaultValue = "true") boolean deleteDocuments) {
        knowledgeService.deleteByFilename(userId, filename, deleteDocuments);
        return ApiResponse.success("文档已删除");
    }

    @GetMapping("/md5/list")
    public ApiResponse<MD5ListResponse> listMd5Records(@UserId String userId) {
        MD5ListResponse result = knowledgeService.listMd5Records(userId);
        return ApiResponse.success(result);
    }

    @GetMapping("/md5/{md5}")
    public ApiResponse<MD5Record> getMd5Info(@UserId String userId, @PathVariable String md5) {
        MD5Record record = knowledgeService.getMd5Info(userId, md5);
        if (record == null) {
            return ApiResponse.error(404, "MD5记录不存在");
        }
        return ApiResponse.success(record);
    }

    @GetMapping("/list")
    public ApiResponse<KnowledgeListResponse> listDocuments(@UserId String userId) {
        KnowledgeListResponse result = knowledgeService.listDocuments(userId);
        return ApiResponse.success(result);
    }

    @GetMapping("/detail")
    public ApiResponse<Map<String, Object>> getDocumentDetail(
            @UserId String userId, @RequestParam String filename) {
        Map<String, Object> detail = knowledgeService.getDocumentDetail(userId, filename);
        return ApiResponse.success(detail);
    }

    @GetMapping("/chunks")
    public ApiResponse<Map<String, Object>> getDocumentChunks(
            @UserId String userId, @RequestParam String filename) {
        Map<String, Object> chunks = knowledgeService.getDocumentChunks(userId, filename);
        return ApiResponse.success(chunks);
    }

    @GetMapping("/image/{md5}/{filename}")
    public void serveImage(@UserId String userId, @PathVariable String md5,
                           @PathVariable String filename) {
        // Would serve image file from extracted_images directory
        throw new com.rag.notebook.common.exception.BusinessException("图片服务暂未实现");
    }

    @GetMapping("/images/all/{md5}")
    public ApiResponse<Map<String, Object>> getBatchImages(@UserId String userId, @PathVariable String md5) {
        return ApiResponse.success(Map.of("images", java.util.List.of()));
    }

    @PostMapping("/retry-vectorization/{docId}")
    public ApiResponse<Void> retryVectorization(@UserId String userId, @PathVariable String docId) {
        knowledgeService.retryVectorization(userId, docId);
        return ApiResponse.success("重试任务已提交");
    }
}
