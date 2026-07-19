package com.rag.notebook.rag;

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
 */
@Slf4j
@Service
public class HybridRetriever {

    /** RRF 公式中的常数 k，通常取 60 */
    private static final int RRF_K = 60;

    private final VectorStoreService vectorStoreService;
    private final Bm25Service bm25Service;
    private final QueryExpander queryExpander;
    private final RerankerService rerankerService;

    public HybridRetriever(VectorStoreService vectorStoreService,
                           Bm25Service bm25Service,
                           QueryExpander queryExpander,
                           RerankerService rerankerService) {
        this.vectorStoreService = vectorStoreService;
        this.bm25Service = bm25Service;
        this.queryExpander = queryExpander;
        this.rerankerService = rerankerService;
    }

    /**
     * 混合检索知识库文档
     *
     * @param userId 用户ID
     * @param query  原始查询
     * @param topK   返回结果数
     * @return RRF 融合 + 精排后的结果
     */
    public List<Map<String, Object>> searchKnowledge(String userId, String query, int topK) {
        // 1. 多 Query 扩展
        List<String> queries = queryExpander.expand(query);
        log.info("知识库混合检索: 原始查询='{}', 扩展为 {} 个版本", truncate(query, 30), queries.size());

        // 2. 收集所有路的检索结果
        List<List<Map<String, Object>>> allRankings = new ArrayList<>();

        for (String q : queries) {
            // 向量检索
            List<Map<String, Object>> vectorResults = vectorStoreService.searchKnowledge(userId, q, topK * 2);
            allRankings.add(vectorResults);

            // BM25 检索
            List<Map<String, Object>> bm25Results = bm25Service.search(userId, q, topK * 2);
            bm25Results = bm25Results.stream()
                    .filter(r -> "knowledge_base".equals(r.get("source")))
                    .collect(Collectors.toList());
            allRankings.add(bm25Results);
        }

        // 3. RRF 融合
        List<Map<String, Object>> fused = rrfFusion(allRankings, topK * 2);

        // 4. Cross-Encoder 精排 + 动态 top-N
        fused = rerankerService.rerank(query, fused);

        log.info("知识库混合检索完成: {} 个查询版本 × {}路, 精排后返回 {} 条",
                queries.size(), allRankings.size(), fused.size());

        return fused;
    }

    /**
     * 混合检索笔记
     */
    public List<Map<String, Object>> searchNotes(String userId, String query, int topK) {
        List<String> queries = queryExpander.expand(query);
        log.info("笔记混合检索: 原始查询='{}', 扩展为 {} 个版本", truncate(query, 30), queries.size());

        List<List<Map<String, Object>>> allRankings = new ArrayList<>();

        for (String q : queries) {
            // 向量检索
            List<Map<String, Object>> vectorResults = vectorStoreService.searchNotes(userId, q, topK * 2);
            allRankings.add(vectorResults);

            // BM25 检索
            List<Map<String, Object>> bm25Results = bm25Service.search(userId, q, topK * 2);
            bm25Results = bm25Results.stream()
                    .filter(r -> "note".equals(r.get("source")))
                    .collect(Collectors.toList());
            allRankings.add(bm25Results);
        }

        // RRF 融合
        List<Map<String, Object>> fused = rrfFusion(allRankings, topK * 2);

        // Cross-Encoder 精排 + 动态 top-N
        fused = rerankerService.rerank(query, fused);

        log.info("笔记混合检索完成: 精排后返回 {} 条", fused.size());

        return fused;
    }

    /**
     * RRF (Reciprocal Rank Fusion) 融合算法
     *
     * 公式: score(d) = Σ 1/(k + rank_i(d))
     * 其中 k=60 是常数，rank_i(d) 是文档 d 在第 i 路检索结果中的排名（从1开始）
     *
     * @param allRankings 所有路的检索结果列表
     * @param topK        最终返回的结果数
     * @return 融合后按 RRF 分数排序的结果
     */
    private List<Map<String, Object>> rrfFusion(List<List<Map<String, Object>>> allRankings, int topK) {
        // RRF 分数累加器：key=文档唯一标识, value=RRF分数
        Map<String, Double> rrfScores = new HashMap<>();
        // 文档内容缓存：key=文档唯一标识, value=文档数据
        Map<String, Map<String, Object>> docCache = new HashMap<>();

        for (List<Map<String, Object>> ranking : allRankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                Map<String, Object> doc = ranking.get(rank);
                String docKey = getDocKey(doc);

                // RRF 分数累加: 1/(k + rank)，rank 从 1 开始
                double rrfScore = 1.0 / (RRF_K + rank + 1);
                rrfScores.merge(docKey, rrfScore, Double::sum);

                // 缓存文档内容（保留最高分的那份）
                docCache.putIfAbsent(docKey, doc);
            }
        }

        // 按 RRF 分数降序排序，取 top-K
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
     * 获取文档的唯一标识（用于 RRF 去重）
     */
    private String getDocKey(Map<String, Object> doc) {
        // 优先用 chunk_id，其次用 docId，最后用 content 的哈希
        if (doc.containsKey("chunk_id")) return (String) doc.get("chunk_id");
        if (doc.containsKey("docId")) return (String) doc.get("docId");
        if (doc.containsKey("note_id")) return (String) doc.get("note_id");
        return String.valueOf(doc.getOrDefault("content", "").hashCode());
    }

    private String truncate(String str, int maxLen) {
        return str != null && str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}
