package com.rag.notebook.rag;

import com.rag.notebook.agent.ModelFactory;
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
            "请将以下查询改写为3个不同的版本，保持语义相同但使用不同的表述方式。\n" +
            "要求：\n" +
            "1. 每个版本一行，不要编号\n" +
            "2. 使用不同的关键词和句式\n" +
            "3. 可以包含同义词、缩写、全称等不同表达\n" +
            "4. 只返回改写结果，不要有其他文字\n" +
            "5. 每个版本不超过20个字\n\n" +
            "原始查询：{query}\n\n" +
            "改写结果：";

    private final ModelFactory modelFactory;

    public QueryExpander(ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    /**
     * 将一个查询扩展为多个语义等价的查询
     *
     * @param query 原始查询（建议传短查询，不要传 HyDE 长文档）
     * @return 包含原始查询和扩展查询的列表（最多 1+3=4 个）
     */
    public List<String> expand(String query) {
        List<String> queries = new ArrayList<>();
        queries.add(query);  // 原始查询始终包含

        // 截断过长的输入，只取前 100 字
        String shortQuery = query.length() > 100 ? query.substring(0, 100) : query;

        try {
            ChatLanguageModel chatModel = modelFactory.createChatModel();
            String prompt = EXPAND_PROMPT.replace("{query}", shortQuery);
            Response<AiMessage> response = chatModel.generate(UserMessage.from(prompt));

            String result = response.content().text();
            String[] lines = result.split("\n");
            for (String line : lines) {
                if (queries.size() >= MAX_EXPANSIONS + 1) break;  // 硬限制：最多 1+3 个

                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.equals(query)) {
                    // 去掉可能的编号前缀（1. 2. 3. - * 等）
                    trimmed = trimmed.replaceFirst("^[\\d.\\-*)\\s]+", "").trim();
                    // 去掉引号
                    trimmed = trimmed.replaceAll("[\"\"']", "").trim();
                    // 限制每个变体长度不超过 50 字
                    if (trimmed.length() > 50) {
                        trimmed = trimmed.substring(0, 50);
                    }
                    if (!trimmed.isEmpty() && !queries.contains(trimmed)) {
                        queries.add(trimmed);
                    }
                }
            }

            log.info("Query 扩展: [{}] → {} 个版本", truncate(query, 30), queries.size());
        } catch (Exception e) {
            log.warn("Query 扩展失败，仅使用原始查询: {}", e.getMessage());
        }

        return queries;
    }

    private String truncate(String str, int maxLen) {
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}
