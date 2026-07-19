package com.rag.notebook.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.notebook.config.ApplicationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Cross-Encoder 精排服务
 * 使用智谱 AI 的 rerank API 对检索结果进行重排序
 */
@Slf4j
@Service
public class RerankerService {

    private final ApplicationProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RerankerService(ApplicationProperties props) {
        this.props = props;
    }

    /**
     * 检查是否启用精排
     */
    public boolean isEnabled() {
        return props.getReranker().isEnabled();
    }

    /**
     * 对文档进行精排
     *
     * @param query      查询文本
     * @param documents  待排序的文档列表
     * @return 精排后的文档列表（已过滤低分文档）
     */
    public List<Map<String, Object>> rerank(String query, List<Map<String, Object>> documents) {
        if (!isEnabled() || documents.isEmpty()) {
            return documents;
        }

        ApplicationProperties.Reranker config = props.getReranker();

        try {
            // 1. 构建请求
            List<String> docTexts = new ArrayList<>();
            for (Map<String, Object> doc : documents) {
                docTexts.add((String) doc.getOrDefault("content", ""));
            }

            // 2. 调用 rerank API
            RerankResponse response = callRerankApi(query, docTexts, config);
            if (response == null || response.results == null) {
                log.warn("Rerank API 返回空结果，跳过精排");
                return documents;
            }

            // 3. 根据精排结果重新排序
            List<Map<String, Object>> rerankedDocs = new ArrayList<>();
            for (RerankResult result : response.results) {
                Map<String, Object> doc = new HashMap<>(documents.get(result.index));
                doc.put("rerank_score", result.relevanceScore);
                rerankedDocs.add(doc);
            }

            // 4. 按精排分数排序
            rerankedDocs.sort((a, b) -> Double.compare(
                    (double) b.getOrDefault("rerank_score", 0.0),
                    (double) a.getOrDefault("rerank_score", 0.0)
            ));

            // 5. 动态 top-N 策略
            rerankedDocs = dynamicTopN(rerankedDocs);

            log.info("精排完成: 输入{}条, 输出{}条", documents.size(), rerankedDocs.size());
            return rerankedDocs;

        } catch (Exception e) {
            log.error("精排失败，返回原始结果: {}", e.getMessage());
            return documents;
        }
    }

    /**
     * 动态 top-N 策略：
     * - 分数 > 0.7：全部保留（高质量结果）
     * - 分数 0.5-0.7：最多保留 2 条（中等质量）
     * - 分数 < 0.5：丢弃
     */
    private List<Map<String, Object>> dynamicTopN(List<Map<String, Object>> rerankedDocs) {
        List<Map<String, Object>> high = new ArrayList<>();
        List<Map<String, Object>> medium = new ArrayList<>();

        for (Map<String, Object> doc : rerankedDocs) {
            double score = (double) doc.getOrDefault("rerank_score", 0.0);
            if (score > 0.7) {
                high.add(doc);
            } else if (score >= 0.5) {
                medium.add(doc);
            }
        }

        // 中等质量最多保留 2 条
        List<Map<String, Object>> result = new ArrayList<>(high);
        result.addAll(medium.subList(0, Math.min(2, medium.size())));

        log.info("动态 top-N: 高质量{}条, 中等质量{}条(保留{}), 丢弃{}条",
                high.size(), medium.size(), Math.min(2, medium.size()),
                rerankedDocs.size() - high.size() - medium.size());

        return result;
    }

    /**
     * 调用智谱 AI rerank API
     */
    private RerankResponse callRerankApi(String query, List<String> documents, ApplicationProperties.Reranker config) {
        try {
            String url = config.getBaseUrl() + "/rerank";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(config.getApiKey());

            Map<String, Object> body = new HashMap<>();
            body.put("model", config.getModel());
            body.put("query", query);
            body.put("documents", documents);
            body.put("top_n", config.getTopN());
            body.put("return_documents", false);

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);

            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return parseResponse(response.getBody());
            }

            log.warn("Rerank API 调用失败: status={}", response.getStatusCode());
            return null;

        } catch (Exception e) {
            log.error("Rerank API 调用异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 rerank API 响应
     */
    private RerankResponse parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            RerankResponse response = new RerankResponse();
            response.results = new ArrayList<>();

            JsonNode resultsNode = root.get("results");
            if (resultsNode != null && resultsNode.isArray()) {
                for (JsonNode resultNode : resultsNode) {
                    RerankResult result = new RerankResult();
                    result.index = resultNode.get("index").asInt();
                    result.relevanceScore = resultNode.get("relevance_score").asDouble();
                    response.results.add(result);
                }
            }

            return response;
        } catch (Exception e) {
            log.error("解析 rerank 响应失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Rerank API 响应
     */
    private static class RerankResponse {
        List<RerankResult> results;
    }

    /**
     * 单条精排结果
     */
    private static class RerankResult {
        int index;
        double relevanceScore;
    }
}
