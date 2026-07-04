package com.rag.notebook.rag;

import com.rag.notebook.config.ApplicationProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BiConsumer;

@Slf4j
@Service
public class DocumentProcessor {

    private final ApplicationProperties props;
    private final VectorStoreService vectorStoreService;
    private final Md5Store md5Store;
    private final Tika tika = new Tika();

    public DocumentProcessor(ApplicationProperties props, VectorStoreService vectorStoreService, Md5Store md5Store) {
        this.props = props;
        this.vectorStoreService = vectorStoreService;
        this.md5Store = md5Store;
    }

    public void processFile(File file, String originalFilename, String userId,
                            BiConsumer<String, Object> progressCallback) {
        try {
            progressCallback.accept("loading", originalFilename);

            String md5 = computeMd5(file);
            // 只有 MD5 记录存在 且 向量数据也存在时才跳过
            // 防止重启后向量数据丢失但 MD5 记录还在导致无法重新上传
            if (md5Store.exists(md5, userId) && vectorStoreService.hasKnowledgeDocument(userId, md5)) {
                progressCallback.accept("skipping", originalFilename);
                return;
            }
            // MD5 记录存在但向量数据丢失，清理旧的 MD5 记录
            if (md5Store.exists(md5, userId)) {
                md5Store.deleteByMd5(md5, userId);
                log.info("MD5 记录存在但向量数据丢失，清理后重新处理: {}", originalFilename);
            }

            progressCallback.accept("splitting", originalFilename);
            String content = tika.parseToString(file);
            List<String> chunks = splitText(content, props.getChroma().getChunkSize(),
                    props.getChroma().getChunkOverlap());

            progressCallback.accept("storing", originalFilename);
            Map<String, Object> metadata = Map.of(
                    "user_id", userId,
                    "original_filename", originalFilename,
                    "md5", md5,
                    "source", "knowledge_base",
                    "created_at", System.currentTimeMillis()
            );
            vectorStoreService.addKnowledgeDocument(userId, originalFilename, md5, chunks, metadata, progressCallback);

            md5Store.save(md5, originalFilename, originalFilename, userId);

            progressCallback.accept("completed", originalFilename);
            log.info("Processed file: {} ({} chunks)", originalFilename, chunks.size());
        } catch (Exception e) {
            progressCallback.accept("error", originalFilename + ": " + e.getMessage());
            log.error("Failed to process file {}: {}", originalFilename, e.getMessage(), e);
        }
    }

    private List<String> splitText(String text, int chunkSize, int chunkOverlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;

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
