package com.rag.notebook.rag;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

@Slf4j
@Service
public class Bm25Service {

    private static final String INDEX_BASE_DIR = "data/bm25_index";

    private final Map<String, Directory> userIndexes = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Map<String, Object>>> userDocMetadata = new ConcurrentHashMap<>();
    private final Analyzer analyzer = new StandardAnalyzer();

    @PostConstruct
    public void init() {
        try {
            Path basePath = Paths.get(INDEX_BASE_DIR);
            Files.createDirectories(basePath);
            log.info("BM25 index base directory initialized: {}", basePath.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to create BM25 index base directory: {}", e.getMessage());
        }
    }

    public synchronized void addDocument(String userId, String docId, String content,
                                         Map<String, Object> metadata) {
        try {
            Directory indexDir = getOrCreateIndex(userId);
            Map<String, Object> safeMetadata = metadata == null ? Map.of() : metadata;
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

            try (IndexWriter writer = new IndexWriter(indexDir, config)) {
                Document doc = new Document();
                doc.add(new TextField("content", content, Field.Store.YES));
                doc.add(new TextField("docId", docId, Field.Store.YES));
                addStoredMetadata(doc, "user_id", userId);
                addStoredMetadata(doc, "source", safeMetadata.get("source"));
                addStoredMetadata(doc, "chunk_id", safeMetadata.get("chunk_id"));
                addStoredMetadata(doc, "doc_id", safeMetadata.get("doc_id"));
                addStoredMetadata(doc, "note_id", safeMetadata.get("note_id"));
                addStoredMetadata(doc, "title", safeMetadata.get("title"));
                addStoredMetadata(doc, "filename", safeMetadata.get("filename"));
                addStoredMetadata(doc, "original_filename", safeMetadata.get("original_filename"));
                addStoredMetadata(doc, "md5", safeMetadata.get("md5"));
                writer.updateDocument(new Term("docId", docId), doc);
                writer.commit();
            }

            userDocMetadata.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                    .put(docId, safeMetadata);

            log.debug("BM25 indexed doc {} for user {}", docId, userId);
        } catch (IOException e) {
            log.warn("BM25 failed to index doc: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> search(String userId, String query, int topK) {
        return search(userId, query, topK, null);
    }

    public List<Map<String, Object>> search(String userId, String query, int topK,
                                            Predicate<Map<String, Object>> resultFilter) {
        Directory indexDir = getOrCreateIndex(userId);

        try (DirectoryReader reader = DirectoryReader.open(indexDir)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            QueryParser parser = new QueryParser("content", analyzer);
            Query luceneQuery = parser.parse(QueryParser.escape(query));
            int candidateLimit = resultFilter == null ? topK : Math.max(topK, reader.maxDoc());
            TopDocs topDocs = searcher.search(luceneQuery, candidateLimit);
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
                putStoredMetadata(result, doc,
                        "user_id", "source", "chunk_id", "doc_id", "note_id",
                        "title", "filename", "original_filename", "md5");
                result.putAll(metadata.getOrDefault(docId, Map.of()));
                if (resultFilter == null || resultFilter.test(result)) {
                    results.add(result);
                    if (results.size() >= topK) {
                        break;
                    }
                }
            }

            return results;
        } catch (Exception e) {
            log.warn("BM25 search failed: {}", e.getMessage());
            return List.of();
        }
    }

    public void deleteDocument(String userId, String docId) {
        deleteDocuments(userId, List.of(docId));
    }

    public synchronized void deleteDocuments(String userId, Collection<String> docIds) {
        Directory indexDir = userIndexes.get(userId);
        if (indexDir == null || docIds == null || docIds.isEmpty()) return;

        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            try (IndexWriter writer = new IndexWriter(indexDir, config)) {
                for (String docId : docIds) {
                    writer.deleteDocuments(new Term("docId", docId));
                }
                writer.commit();
            }

            Map<String, Map<String, Object>> metadata = userDocMetadata.get(userId);
            if (metadata != null) {
                for (String docId : docIds) {
                    metadata.remove(docId);
                }
            }
        } catch (IOException e) {
            log.warn("BM25 failed to delete docs: {}", e.getMessage());
        }
    }

    public synchronized void clearUserIndex(String userId) {
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
                log.warn("BM25 failed to clear index: {}", e.getMessage());
            }
        }
        userDocMetadata.remove(userId);
        log.debug("BM25 cleared index for user {}", userId);
    }

    private void addStoredMetadata(Document doc, String key, Object value) {
        if (value == null) {
            return;
        }
        String str = String.valueOf(value);
        if (!str.isBlank() && !"null".equalsIgnoreCase(str)) {
            doc.add(new StringField(key, str, Field.Store.YES));
        }
    }

    private void putStoredMetadata(Map<String, Object> result, Document doc, String... keys) {
        for (String key : keys) {
            String value = doc.get(key);
            if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value)) {
                result.put(key, value);
            }
        }
    }

    private Directory getOrCreateIndex(String userId) {
        return userIndexes.computeIfAbsent(userId, k -> {
            try {
                Path indexPath = Paths.get(INDEX_BASE_DIR, userId);
                Files.createDirectories(indexPath);
                FSDirectory fsDir = FSDirectory.open(indexPath);
                log.info("BM25 index directory loaded: userId={}, path={}", userId, indexPath.toAbsolutePath());
                return fsDir;
            } catch (IOException e) {
                log.error("Failed to create BM25 index directory: userId={}, error={}", userId, e.getMessage());
                throw new RuntimeException("Failed to create BM25 index directory", e);
            }
        });
    }

    public Set<String> getLoadedUserIndexes() {
        return Collections.unmodifiableSet(userIndexes.keySet());
    }

    public boolean hasUserIndex(String userId) {
        Path indexPath = Paths.get(INDEX_BASE_DIR, userId);
        return Files.exists(indexPath) && Files.isDirectory(indexPath);
    }
}
