package com.rag.notebook.rag;

import com.rag.notebook.config.ApplicationProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

@Slf4j
@Service
public class DocumentProcessor {

    private static final int MAX_EXTRACTED_TEXT_LENGTH = Integer.MAX_VALUE;

    private final ApplicationProperties props;
    private final VectorStoreService vectorStoreService;
    private final Md5Store md5Store;
    private final DocumentTaskExecutor documentTaskExecutor;
    private final Tika tika = new Tika();

    public DocumentProcessor(ApplicationProperties props, VectorStoreService vectorStoreService,
                             Md5Store md5Store, DocumentTaskExecutor documentTaskExecutor) {
        this.props = props;
        this.vectorStoreService = vectorStoreService;
        this.md5Store = md5Store;
        this.documentTaskExecutor = documentTaskExecutor;
        this.tika.setMaxStringLength(MAX_EXTRACTED_TEXT_LENGTH);
    }

    public CompletableFuture<Void> processFile(File file, String originalFilename, String userId,
                                                BiConsumer<String, Object> progressCallback) {
        try {
            progressCallback.accept("loading", originalFilename);

            String md5 = computeMd5(file);
            if (md5Store.exists(md5, userId) && vectorStoreService.hasKnowledgeDocument(userId, md5)) {
                progressCallback.accept("skipping", originalFilename);
                return CompletableFuture.completedFuture(null);
            }
            if (md5Store.exists(md5, userId)) {
                md5Store.deleteByMd5(md5, userId);
                log.info("MD5 记录存在但向量数据丢失，清理后重新处理: {}", originalFilename);
            }

            progressCallback.accept("splitting", originalFilename);
            String content = extractText(file, originalFilename);
            log.info("Document text extracted: filename={}, chars={}", originalFilename, content.length());
            String filePrefix = "[文件: " + originalFilename + "]\n";
            List<RagChunk> chunks = RagChunker.splitKnowledge(originalFilename, content,
                    props.getChroma().getChunkSize(), props.getChroma().getChunkOverlap());
            log.info("Document text split: filename={}, chunks={}", originalFilename, chunks.size());
            List<String> qualityWarnings = RagChunker.validate(chunks, props.getChroma().getChunkSize(), isPdf(originalFilename));
            if (!qualityWarnings.isEmpty()) {
                log.warn("Document chunk quality warnings: filename={}, warnings={}", originalFilename, qualityWarnings);
            }

            progressCallback.accept("storing", originalFilename);
            Map<String, Object> metadata = Map.of(
                    "user_id", userId,
                    "original_filename", originalFilename,
                    "md5", md5,
                    "source", "knowledge_base",
                    "created_at", System.currentTimeMillis()
            );

            // 1. MySQL + BM25 保存（同步，快速，事务短）
            String docId = vectorStoreService.addKnowledgeDocumentChunks(userId, originalFilename, md5, chunks, metadata, progressCallback);
            if (docId == null) {
                return CompletableFuture.completedFuture(null);
            }

            // 2. 保存MD5记录（同步，快速）
            md5Store.save(md5, originalFilename, originalFilename, userId);

            // 3. 异步 ChromaDB 写入（documentExecutor线程池，不占用事务连接）
            //    完成后自动回调"completed"/"error"事件
            return documentTaskExecutor.writeToChromaAsync(userId, originalFilename, md5, docId, chunks, progressCallback);

        } catch (DataIntegrityViolationException e) {
            if (e.getMessage() != null && e.getMessage().contains("uk_user_md5")) {
                log.info("文档已在并发处理中上传，跳过: {}", originalFilename);
                progressCallback.accept("skipping", originalFilename);
                return CompletableFuture.completedFuture(null);
            } else {
                progressCallback.accept("error", originalFilename + ": " + e.getMessage());
                log.error("Failed to process file {}: {}", originalFilename, e.getMessage(), e);
                return CompletableFuture.failedFuture(e);
            }
        } catch (Exception e) {
            progressCallback.accept("error", originalFilename + ": " + e.getMessage());
            log.error("Failed to process file {}: {}", originalFilename, e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    private List<String> splitText(String text, int chunkSize, int chunkOverlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;
        chunks = TextChunker.split(text, chunkSize, chunkOverlap);
        if (!chunks.isEmpty()) {
            return chunks;
        }

        // Split by Chinese-aware separators
        String[] separators = {"\n\n", "\n", "。", "！", "？", ".", "!", "?", "；", ";", "，", ","};
        List<String> sentences = splitBySeparators(text, separators);

        StringBuilder currentChunk = new StringBuilder();
        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > chunkSize && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString().trim());
                // Keep overlap
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
            if (result.size() > 1) break; // Found a good separator
        }

        return result;
    }

    private String extractText(File file, String originalFilename) throws Exception {
        if (isPdf(originalFilename)) {
            return extractPdfTextByPage(file, originalFilename);
        }
        return tika.parseToString(file);
    }

    private boolean isPdf(String originalFilename) {
        return originalFilename != null
                && originalFilename.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private String extractPdfTextByPage(File file, String originalFilename) throws IOException {
        try (PDDocument document = PDDocument.load(file)) {
            int pageCount = document.getNumberOfPages();
            log.info("PDF page extraction started: filename={}, pages={}", originalFilename, pageCount);

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            StringBuilder content = new StringBuilder();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);

                String pageText = stripper.getText(document);
                if (pageText == null || pageText.isBlank()) {
                    log.debug("PDF page has no extractable text: filename={}, page={}/{}",
                            originalFilename, page, pageCount);
                    continue;
                }

                content.append("\n[Page ")
                        .append(page)
                        .append("/")
                        .append(pageCount)
                        .append("]\n")
                        .append(pageText.strip())
                        .append('\n');
            }

            log.info("PDF page extraction finished: filename={}, pages={}, chars={}",
                    originalFilename, pageCount, content.length());
            return content.toString();
        }
    }

    public String computeMd5(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream is = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public String computeMd5(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(data);
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
