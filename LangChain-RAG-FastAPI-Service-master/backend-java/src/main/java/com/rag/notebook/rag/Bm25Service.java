package com.rag.notebook.rag;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Lucene 的 BM25 检索服务
 * 为每个用户维护独立的磁盘索引，支持中英文 BM25 评分检索
 * 索引持久化到磁盘，应用重启后自动加载，无需重建
 */
@Slf4j
@Service
public class Bm25Service {

    private static final String INDEX_BASE_DIR = "data/bm25_index";

    // 每个用户独立的索引：key=userId
    private final Map<String, Directory> userIndexes = new ConcurrentHashMap<>();
    // 每个用户的文档元数据：key=userId, value=Map<docId, metadata>
    private final Map<String, Map<String, Map<String, Object>>> userDocMetadata = new ConcurrentHashMap<>();

    private final Analyzer analyzer = new StandardAnalyzer();

    @PostConstruct
    public void init() {
        // 确保索引基础目录存在
        try {
            Path basePath = Paths.get(INDEX_BASE_DIR);
            Files.createDirectories(basePath);
            log.info("BM25索引目录已初始化: {}", basePath.toAbsolutePath());
        } catch (IOException e) {
            log.error("创建BM25索引目录失败: {}", e.getMessage());
        }
    }

    /**
     * 添加文档到用户的 BM25 索引
     */
    public void addDocument(String userId, String docId, String content,
                            Map<String, Object> metadata) {
        try {
            Directory indexDir = getOrCreateIndex(userId);
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

            try (IndexWriter writer = new IndexWriter(indexDir, config)) {
                Document doc = new Document();
                doc.add(new TextField("content", content, Field.Store.YES));
                doc.add(new TextField("docId", docId, Field.Store.YES));
                writer.addDocument(doc);
                writer.commit();
            }

            // 存储元数据
            userDocMetadata.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                    .put(docId, metadata);

            log.debug("BM25 indexed doc {} for user {}", docId, userId);
        } catch (IOException e) {
            log.warn("BM25 索引文档失败: {}", e.getMessage());
        }
    }

    /**
     * BM25 检索：返回按 BM25 分数排序的结果
     */
    public List<Map<String, Object>> search(String userId, String query, int topK) {
        Directory indexDir = userIndexes.get(userId);
        if (indexDir == null) return List.of();

        try {
            DirectoryReader reader = DirectoryReader.open(indexDir);
            IndexSearcher searcher = new IndexSearcher(reader);

            QueryParser parser = new QueryParser("content", analyzer);
            // 对查询中的特殊字符转义
            String escapedQuery = QueryParser.escape(query);
            Query luceneQuery = parser.parse(escapedQuery);

            TopDocs topDocs = searcher.search(luceneQuery, topK);
            List<Map<String, Object>> results = new ArrayList<>();

            Map<String, Map<String, Object>> metadata = userDocMetadata
                    .getOrDefault(userId, Map.of());

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.doc(scoreDoc.doc);
                String docId = doc.get("docId");
                String content = doc.get("content");

                Map<String, Object> result = new HashMap<>();
                result.put("docId", docId);
                result.put("content", content);
                result.put("bm25_score", scoreDoc.score);
                // 合并元数据
                Map<String, Object> meta = metadata.getOrDefault(docId, Map.of());
                result.putAll(meta);

                results.add(result);
            }

            reader.close();
            return results;
        } catch (Exception e) {
            log.warn("BM25 检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 删除用户索引中的某个文档
     */
    public void deleteDocument(String userId, String docId) {
        Directory indexDir = userIndexes.get(userId);
        if (indexDir == null) return;

        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            try (IndexWriter writer = new IndexWriter(indexDir, config)) {
                writer.deleteDocuments(new Term("docId", docId));
                writer.commit();
            }
            Map<String, Map<String, Object>> metadata = userDocMetadata.get(userId);
            if (metadata != null) metadata.remove(docId);
        } catch (IOException e) {
            log.warn("BM25 删除文档失败: {}", e.getMessage());
        }
    }

    /**
     * 清空用户的 BM25 索引
     */
    public void clearUserIndex(String userId) {
        Directory indexDir = userIndexes.get(userId);
        if (indexDir != null) {
            try {
                IndexWriterConfig config = new IndexWriterConfig(analyzer);
                config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
                try (IndexWriter writer = new IndexWriter(indexDir, config)) {
                    writer.deleteAll();
                    writer.commit();
                }
            } catch (IOException e) {
                log.warn("清空BM25索引失败: {}", e.getMessage());
            }
        }
        userDocMetadata.remove(userId);
        log.debug("BM25 cleared index for user {}", userId);
    }

    /**
     * 获取或创建用户的索引目录（磁盘持久化）
     */
    private Directory getOrCreateIndex(String userId) {
        return userIndexes.computeIfAbsent(userId, k -> {
            try {
                Path indexPath = Paths.get(INDEX_BASE_DIR, userId);
                Files.createDirectories(indexPath);
                FSDirectory fsDir = FSDirectory.open(indexPath);
                log.info("BM25索引目录已创建/加载: userId={}, path={}", userId, indexPath.toAbsolutePath());
                return fsDir;
            } catch (IOException e) {
                log.error("创建BM25索引目录失败: userId={}, error={}", userId, e.getMessage());
                throw new RuntimeException("创建BM25索引目录失败", e);
            }
        });
    }

    /**
     * 获取已加载的用户索引列表（用于诊断）
     */
    public Set<String> getLoadedUserIndexes() {
        return Collections.unmodifiableSet(userIndexes.keySet());
    }

    /**
     * 检查用户索引是否存在
     */
    public boolean hasUserIndex(String userId) {
        Path indexPath = Paths.get(INDEX_BASE_DIR, userId);
        return Files.exists(indexPath) && Files.isDirectory(indexPath);
    }
}
