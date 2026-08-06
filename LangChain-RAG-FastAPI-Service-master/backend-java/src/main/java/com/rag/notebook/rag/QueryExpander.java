package com.rag.notebook.rag;

import com.rag.notebook.agent.ModelFactory;
import com.rag.notebook.cache.QueryCacheService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 多 Query 扩展服务
 * 将用户的原始查询改写为多个语义等价但表述不同的版本，
 * 用于多路检索提升召回率。
 */
@Slf4j
@Service
public class QueryExpander {

    /** 最大扩展数量（不含原始查询） */
    private static final int MAX_EXPANSIONS = 3;

    private static final String EXPAND_PROMPT =
            "你是一个专业的RAG检索优化助手。"
                    + "请针对用户查询生成3个用于知识库检索的扩展查询。\n"
                    + "\n"
                    + "要求：\n"
                    + "1. 保留原始查询的核心意图，不改变问题方向\n"
                    + "2. 从不同检索角度扩展，包括：同义词、专业术语、相关概念、文档常见表达\n"
                    + "3. 每个查询必须能够独立用于搜索知识库\n"
                    + "4. 不要生成答案，不要解释原因\n"
                    + "5. 避免简单重复原查询或罗列大量关键词\n"
                    + "6. 每个查询长度控制在10~30个字\n"
                    + "7. 只输出查询列表，每行一个，不要编号\n"
                    + "\n"
                    + "示例：\n"
                    + "输入：垃圾回收优化\n"
                    + "输出：\n"
                    + "JVM垃圾回收机制优化\n"
                    + "GC调优策略与性能分析\n"
                    + "G1 CMS收集器优化方法\n"
                    + "\n"
                    + "当前查询：{query}\n"
                    + "扩展查询：";

/*
            "请将以下查询改写为3个不同的版本，用于从知识库中检索相关文档。\n" +
            "要求：\n" +
            "1. 每个版本一行，不要编号\n" +
            "2. 如果查询是抽象概念（如'简历'、'项目方案'），请展开为具体内容维度（如教育背景、工作经历、技能清单）\n" +
            "3. 使用具体的关键词，避免抽象词汇\n" +
            "4. 可以包含同义词、缩写、全称等不同表达\n" +
            "5. 只返回改写结果，不要有其他文字\n" +
            "6. 每个版本不超过20个字\n\n" +
            "原始查询：{query}\n\n" +
            "改写结果：";
*/

    private final ModelFactory modelFactory;
    private final QueryCacheService queryCacheService;

    /** 最近一次扩展的 Token 消耗 */
    private volatile int lastExpansionTokens = 0;

    public QueryExpander(ModelFactory modelFactory, QueryCacheService queryCacheService) {
        this.modelFactory = modelFactory;
        this.queryCacheService = queryCacheService;
    }

    /** 获取最近一次扩展消耗的 Token 数 */
    public int getLastExpansionTokens() {
        return lastExpansionTokens;
    }

    /**
     * 将一个查询扩展为多个语义等价的查询
     *
     * @param query 原始查询（建议传短查询，不要传 HyDE 长文档）
     * @return 包含原始查询和扩展查询的列表（最多 1+3=4 个）
     */
    public List<String> expand(String query) {
        String key = queryCacheService.hashKey("query-cache:query-expansion", query);
        List<String> cached = queryCacheService.get(key, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        if (cached != null) return cached;
        List<String> result = expandUncached(query);
        queryCacheService.put(key, result, java.time.Duration.ofMinutes(30));
        return result;
    }

    private List<String> expandUncached(String query) {
        List<String> queries = new ArrayList<>();
        queries.add(query);  // 原始查询始终包含

        // 截断过长的输入，只取前 100 字
        String shortQuery = query.length() > 100 ? query.substring(0, 100) : query;

        try {
            ChatLanguageModel chatModel = modelFactory.createCreativeModel();
            String prompt = EXPAND_PROMPT.replace("{query}", shortQuery);
            Response<AiMessage> response = chatModel.generate(UserMessage.from(prompt));

            // 记录 Token 消耗
            if (response.tokenUsage() != null) {
                lastExpansionTokens = response.tokenUsage().totalTokenCount();
            } else {
                lastExpansionTokens = 0;
            }

            String result = response.content().text();
            String[] lines = result.split("\n");
            for (String line : lines) {
                if (queries.size() >= MAX_EXPANSIONS + 1) break;

                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.equals(query)) {
                    trimmed = trimmed.replaceFirst("^[\\d.\\-*)\\s]+", "").trim();
                    trimmed = trimmed.replaceAll("[\"\"']", "").trim();
                    if (trimmed.length() > 50) {
                        trimmed = trimmed.substring(0, 50);
                    }
                    if (!trimmed.isEmpty() && !queries.contains(trimmed)) {
                        queries.add(trimmed);
                    }
                }
            }

            log.info("Query 扩展: [{}] → {} 个版本, tokens={}", truncate(query, 30), queries.size(), lastExpansionTokens);
            log.info("Expanded queries: {}", queries);
        } catch (Exception e) {
            log.warn("Query 扩展失败，仅使用原始查询: {}", e.getMessage());
            lastExpansionTokens = 0;
        }

        return queries;
    }

    private String truncate(String str, int maxLen) {
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}
