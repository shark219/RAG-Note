package com.rag.notebook.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
public class ReorderService {

    /**
     * Reorder documents by relevance score.
     * In production, this would use a cross-encoder model (ONNX Runtime or remote service).
     * Currently uses a simple keyword-overlap heuristic as placeholder.
     */
    public Map<String, Object> reorderDocuments(String query, List<String> documents) {
        try {
            List<String> queryTerms = Arrays.asList(query.toLowerCase().split("\\s+"));

            List<Map<String, Object>> scored = IntStream.range(0, documents.size())
                    .mapToObj(i -> {
                        String doc = documents.get(i).toLowerCase();
                        long matchCount = queryTerms.stream()
                                .filter(term -> doc.contains(term))
                                .count();
                        float score = (float) matchCount / Math.max(1, queryTerms.size());
                        return Map.<String, Object>of("document", documents.get(i), "similarity", score);
                    })
                    .sorted((a, b) -> Float.compare(
                            (float) b.get("similarity"), (float) a.get("similarity")))
                    .collect(Collectors.toList());

            return Map.of("success", true, "documents", scored, "error", "");
        } catch (Exception e) {
            log.warn("Reranking failed: {}", e.getMessage());
            List<Map<String, Object>> fallback = documents.stream()
                    .map(doc -> Map.<String, Object>of("document", doc, "similarity", 0.0f))
                    .collect(Collectors.toList());
            return Map.of("success", false, "documents", fallback, "error", e.getMessage());
        }
    }
}
