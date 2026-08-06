package com.rag.notebook.rag;

import com.rag.notebook.config.ApplicationProperties;
import com.rag.notebook.evaluation.dto.AblationConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索服务：向量检索 + BM25 + 多 Query 扩展 + RRF 融合 + Cross-Encoder 精排
 *
 * 检索流程：
 * 1. 多 Query 扩展：将用户查询改写为 N 个语义等价版本（不限长度）
 * 2. 每个查询版本分别执行：向量检索 + BM25 检索
 * 3. RRF 融合：将所有路的检索结果用 Reciprocal Rank Fusion 合并排序
 * 4. Cross-Encoder 精排：使用智谱 AI rerank API 重新打分 + 阈值过滤
 *
 * 支持消融实验：通过 AblationConfig 控制各组件开关
 */

@Slf4j
@Service
public class HybridRetriever {

    /** RRF 公式中的常数 k 默认值，通常取 60 */
    private static final int DEFAULT_RRF_K = 30;

    private final VectorStoreService vectorStoreService;
    private final Bm25Service bm25Service;
    private final QueryExpander queryExpander;
    private final RerankerService rerankerService;
    private final ApplicationProperties props;
    private final KeywordSearchService keywordSearchService;

    /** 最近一次检索的 Query Expansion Token 消耗 */
    private volatile int lastQueryExpansionTokens = 0;

    public HybridRetriever(VectorStoreService vectorStoreService,
                           Bm25Service bm25Service,
                           QueryExpander queryExpander,
                           RerankerService rerankerService,
                           ApplicationProperties props,
                           KeywordSearchService keywordSearchService) {
        this.vectorStoreService = vectorStoreService;
        this.bm25Service = bm25Service;
        this.queryExpander = queryExpander;
        this.rerankerService = rerankerService;
        this.props = props;
        this.keywordSearchService = keywordSearchService;
    }

    /** 获取最近一次检索中 Query Expansion 消耗的 Token 数 */
    public int getLastQueryExpansionTokens() {
        return lastQueryExpansionTokens;
    }

    /** 重置 Query Expansion Token 计数（每次检索前调用） */
    public void resetQueryExpansionTokens() {
        lastQueryExpansionTokens = 0;
    }

    // ==================== 公开接口（无消融配置，使用默认配置） ====================

    public List<Map<String, Object>> searchKnowledge(String userId, String query, int topK) {
        return searchKnowledge(userId, query, topK, (AblationConfig) null);
    }

    public List<Map<String, Object>> searchKnowledge(String userId, String query, int topK,
                                                     Set<String> knowledgeDocIdentifiers) {
        return searchKnowledge(userId, query, topK, null, knowledgeDocIdentifiers);
    }

    public List<Map<String, Object>> searchNotes(String userId, String query, int topK) {
        return searchNotes(userId, query, topK, null);
    }

    // ==================== 消融实验接口（接受 AblationConfig） ====================

    /**
     * 混合检索知识库文档（支持消融实验）
     *
     * @param userId 用户ID
     * @param query  原始查询
     * @param topK   返回结果数
     * @param config 消融配置，null 表示使用完整流水线
     * @return 检索结果
     */
    public List<Map<String, Object>> searchKnowledge(String userId, String query, int topK, AblationConfig config) {
        return searchKnowledge(userId, query, topK, config, Set.of());
    }

    public List<Map<String, Object>> searchKnowledge(String userId, String query, int topK, AblationConfig config,
                                                     Set<String> knowledgeDocIdentifiers) {
        boolean expandQuery = isEnabled(config, c -> c.isQueryExpansionEnabled(), props.getAblation().getRag().isQueryExpansionEnabled());
        boolean useVector = isEnabled(config, c -> c.isVectorSearchEnabled(), props.getAblation().getRag().isVectorSearchEnabled());
        boolean useBm25 = isEnabled(config, c -> c.isBm25SearchEnabled(), props.getAblation().getRag().isBm25SearchEnabled());
        boolean useRrf = isEnabled(config, c -> c.isRrfFusionEnabled(), props.getAblation().getRag().isRrfFusionEnabled());
        boolean useRerank = isEnabled(config, c -> c.isRerankEnabled(), props.getAblation().getRag().isRerankEnabled());
        boolean chromaUp = vectorStoreService.isChromaAvailable();
        if (!chromaUp) {
            useVector = false;
            if (!useBm25) {
                log.info("知识库降级到关键词检索: vector unavailable and bm25 disabled");
            }
        }
        if (!bm25Service.isAvailable()) {
            useBm25 = false;
        }
        int effectiveTopK = config != null && config.getTopK() != null ? config.getTopK() : topK;
        int effectiveRrfK = config != null && config.getRrfK() != null ? config.getRrfK() : DEFAULT_RRF_K;
        Set<String> selectedKnowledgeDocs = normalizeIdentifiers(knowledgeDocIdentifiers);

        // 1. Query 扩展
        List<String> queries = expandQuery
                ? queryExpander.expand(query)
                : List.of(query);
        if (expandQuery) {
            lastQueryExpansionTokens += queryExpander.getLastExpansionTokens();
        }
        log.info("知识库混合检索: query='{}', expand={}, vector={}, bm25={}, rrf={}, rerank={}, queries={}, qeTokens={}",
                truncate(query, 30), expandQuery, useVector, useBm25, useRrf, useRerank, queries.size(), lastQueryExpansionTokens);

        // 2. 收集各路检索结果
        if (!selectedKnowledgeDocs.isEmpty()) {
            log.info("Knowledge selected-doc retrieval enabled: selected={}", selectedKnowledgeDocs);
        }
        List<List<Map<String, Object>>> allRankings = new ArrayList<>();

        for (String q : queries) {
            if (useVector) {
                List<Map<String, Object>> vectorResults = vectorStoreService.searchKnowledge(userId, q,
                        effectiveTopK * 2, selectedKnowledgeDocs);
                allRankings.add(vectorResults);
            }
            if (useBm25) {
                Bm25Service.SearchOutcome outcome = bm25Service.searchWithStatus(userId, q, effectiveTopK * 2,
                        r -> matchesKnowledgeSource(r, selectedKnowledgeDocs)
                                && matchesKnowledgeIdentifiers(r, selectedKnowledgeDocs));
                if (outcome.success()) {
                    allRankings.add(outcome.results());
                } else {
                    log.warn("知识库 BM25 检索异常，降级到 MySQL 关键词: {}", outcome.error());
                    allRankings.add(keywordFallbackKnowledge(userId, q, effectiveTopK * 2, selectedKnowledgeDocs));
                }
            }
        }

        if (allRankings.isEmpty()) {
            log.info("知识库混合检索降级到纯关键词检索");
            return keywordFallbackKnowledge(userId, query, effectiveTopK, selectedKnowledgeDocs);
        }

        List<Map<String, Object>> fused;
        if (useRrf && allRankings.size() > 1) {
            fused = rrfFusion(allRankings, effectiveTopK * 2, effectiveRrfK);
        } else {
            fused = simpleMerge(allRankings, effectiveTopK * 2);
        }

        if (useRerank) {
            fused = rerankerService.rerank(query, fused);
        } else {
            fused = fused.stream().limit(effectiveTopK).collect(Collectors.toList());
        }

        log.info("知识库混合检索完成: {} 条查询 × {} 路, 最终返回 {} 条",
                queries.size(), allRankings.size(), fused.size());

        return fused;
    }
    /**
     * 混合检索笔记（支持消融实验）
     */
    public List<Map<String, Object>> searchNotes(String userId, String query, int topK, AblationConfig config) {
        boolean expandQuery = isEnabled(config, c -> c.isQueryExpansionEnabled(), props.getAblation().getRag().isQueryExpansionEnabled());
        boolean useVector = isEnabled(config, c -> c.isVectorSearchEnabled(), props.getAblation().getRag().isVectorSearchEnabled());
        boolean useBm25 = isEnabled(config, c -> c.isBm25SearchEnabled(), props.getAblation().getRag().isBm25SearchEnabled());
        boolean useRrf = isEnabled(config, c -> c.isRrfFusionEnabled(), props.getAblation().getRag().isRrfFusionEnabled());
        boolean useRerank = isEnabled(config, c -> c.isRerankEnabled(), props.getAblation().getRag().isRerankEnabled());
        boolean chromaUp = vectorStoreService.isChromaAvailable();
        if (!chromaUp) {
            useVector = false;
            if (!useBm25) {
                log.info("笔记降级到关键词检索: vector unavailable and bm25 disabled");
            }
        }
        if (!bm25Service.isAvailable()) {
            useBm25 = false;
        }
        int effectiveTopK = config != null && config.getTopK() != null ? config.getTopK() : topK;

        List<String> queries = expandQuery
                ? queryExpander.expand(query)
                : List.of(query);
        if (expandQuery) {
            lastQueryExpansionTokens += queryExpander.getLastExpansionTokens();
        }
        log.info("笔记混合检索: query='{}', expand={}, vector={}, bm25={}, rrf={}, rerank={}, queries={}, qeTokens={}",
                truncate(query, 30), expandQuery, useVector, useBm25, useRrf, useRerank, queries.size(), lastQueryExpansionTokens);

        List<List<Map<String, Object>>> allRankings = new ArrayList<>();

        for (String q : queries) {
            if (useVector) {
                List<Map<String, Object>> vectorResults = vectorStoreService.searchNotes(userId, q, effectiveTopK * 2);
                allRankings.add(vectorResults);
            }
            if (useBm25) {
                Bm25Service.SearchOutcome outcome = bm25Service.searchWithStatus(userId, q, effectiveTopK * 2,
                        r -> "note".equals(r.get("source")));
                if (outcome.success()) {
                    allRankings.add(outcome.results());
                } else {
                    log.warn("笔记 BM25 检索异常，降级到 MySQL 关键词: {}", outcome.error());
                    allRankings.add(keywordFallbackNotes(userId, q, effectiveTopK * 2));
                }
            }
        }

        if (allRankings.isEmpty()) {
            log.info("笔记混合检索降级到纯关键词检索");
            return keywordFallbackNotes(userId, query, effectiveTopK);
        }

        List<Map<String, Object>> fused;
        if (useRrf && allRankings.size() > 1) {
            fused = rrfFusion(allRankings, effectiveTopK * 2, DEFAULT_RRF_K);
        } else {
            fused = simpleMerge(allRankings, effectiveTopK * 2);
        }

        if (useRerank) {
            fused = rerankerService.rerank(query, fused);
        } else {
            fused = fused.stream().limit(effectiveTopK).collect(Collectors.toList());
        }

        log.info("笔记混合检索完成: 最终返回 {} 条", fused.size());

        return fused;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * RRF (Reciprocal Rank Fusion) 融合算法
     *
     * @param rrfK RRF 公式中的常数 k，用于控制排名靠后结果的分数衰减速度（支持消融/参数实验）
     */
    private List<Map<String, Object>> rrfFusion(List<List<Map<String, Object>>> allRankings, int topK, int rrfK) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Map<String, Object>> docCache = new HashMap<>();

        for (List<Map<String, Object>> ranking : allRankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                Map<String, Object> doc = ranking.get(rank);
                String docKey = getDocKey(doc);
                double rrfScore = 1.0 / (rrfK + rank + 1);
                rrfScores.merge(docKey, rrfScore, Double::sum);
                docCache.putIfAbsent(docKey, doc);
            }
        }

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    Map<String, Object> doc = new HashMap<>(docCache.get(entry.getKey()));
                    doc.put("rrf_score", entry.getValue());
                    return doc;
                })
                .collect(Collectors.toList());
    }

    /**
     * 简单合并：去重 + 按原始相似度/分数排序（用于 RRF 被消融时）
     */
    private List<Map<String, Object>> simpleMerge(List<List<Map<String, Object>>> allRankings, int topK) {
        Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
        for (List<Map<String, Object>> ranking : allRankings) {
            for (Map<String, Object> doc : ranking) {
                String key = getDocKey(doc);
                seen.putIfAbsent(key, doc);
            }
        }
        return seen.values().stream()
                .sorted((a, b) -> Double.compare(
                        (double) b.getOrDefault("similarity", 0.0),
                        (double) a.getOrDefault("similarity", 0.0)))
                .limit(topK)
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> keywordFallbackKnowledge(String userId, String query, int topK,
                                                               Set<String> selectedKnowledgeDocs) {
        return keywordSearchService.searchKnowledge(userId, query, topK, selectedKnowledgeDocs);
    }

    private List<Map<String, Object>> keywordFallbackNotes(String userId, String query, int topK) {
        return keywordSearchService.searchNotes(userId, query, topK);
    }

    private String getDocKey(Map<String, Object> doc) {
        if (doc.containsKey("chunk_id")) return (String) doc.get("chunk_id");
        if (doc.containsKey("docId")) return (String) doc.get("docId");
        if (doc.containsKey("note_id")) return (String) doc.get("note_id");
        return String.valueOf(doc.getOrDefault("content", "").hashCode());
    }

    private Set<String> normalizeIdentifiers(Set<String> identifiers) {
        if (identifiers == null || identifiers.isEmpty()) {
            return Set.of();
        }
        return identifiers.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean matchesKnowledgeIdentifiers(Map<String, Object> result, Set<String> identifiers) {
        if (identifiers == null || identifiers.isEmpty()) {
            return true;
        }
        if (matchesAny(identifiers,
                result.get("doc_id"),
                result.get("docId"),
                result.get("id"),
                result.get("md5"),
                result.get("filename"),
                result.get("original_filename"))) {
            return true;
        }

        String chunkId = stringValue(result.get("chunk_id"));
        if (chunkId == null) {
            chunkId = stringValue(result.get("docId"));
        }
        if (chunkId == null) {
            return false;
        }
        String finalChunkId = chunkId;
        return identifiers.stream().anyMatch(id -> finalChunkId.startsWith(id + "_"));
    }

    private boolean matchesKnowledgeSource(Map<String, Object> result, Set<String> selectedIdentifiers) {
        String source = stringValue(result.get("source"));
        if ("knowledge_base".equals(source)) {
            return true;
        }
        return source == null
                && selectedIdentifiers != null
                && !selectedIdentifiers.isEmpty()
                && matchesKnowledgeIdentifiers(result, selectedIdentifiers);
    }

    private boolean matchesAny(Set<String> identifiers, Object... values) {
        for (Object value : values) {
            String str = stringValue(value);
            if (str != null && identifiers.contains(str)) {
                return true;
            }
        }
        return false;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String str = String.valueOf(value);
        return str.isBlank() || "null".equalsIgnoreCase(str) ? null : str;
    }

    private String truncate(String str, int maxLen) {
        return str != null && str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }

    /**
     * 判断组件是否启用：优先使用 AblationConfig（实验模式），其次使用 application.yml 默认值
     */
    private boolean isEnabled(AblationConfig config, java.util.function.Function<AblationConfig, Boolean> getter, boolean defaultValue) {
        if (config != null) {
            return getter.apply(config);
        }
        return defaultValue;
    }
}
