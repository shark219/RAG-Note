package com.rag.notebook.rag;

import com.rag.notebook.agent.ModelFactory;
import com.rag.notebook.config.ApplicationProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagService {

    private static final String HYDE_PROMPT = "基于以下问题，生成一个详细的假设性回答，我会根据你的这个假设性回答在向量数据库里检索文档：\n\n问题：%s\n\n假设性回答：";

    private final VectorStoreService vectorStoreService;
    private final ModelFactory modelFactory;
    private final ApplicationProperties props;
    private final Executor taskExecutor;

    public RagService(VectorStoreService vectorStoreService, ModelFactory modelFactory,
                      ApplicationProperties props, Executor taskExecutor) {
        this.vectorStoreService = vectorStoreService;
        this.modelFactory = modelFactory;
        this.props = props;
        this.taskExecutor = taskExecutor;
    }

    public String generateHypotheticalDocument(String query) {
        try {
            ChatLanguageModel chatModel = modelFactory.createChatModel();
            String prompt = String.format(HYDE_PROMPT, query);
            Response<AiMessage> response = chatModel.generate(UserMessage.from(prompt));
            return response.content().text();
        } catch (Exception e) {
            log.warn("HyDE generation failed, using raw query: {}", e.getMessage());
            return query;
        }
    }

    public List<Map<String, Object>> retrieveDocuments(String userId, String query) {
        String hypotheticalDoc = generateHypotheticalDocument(query);

        // Search knowledge base
        List<Map<String, Object>> knowledgeResults = vectorStoreService.searchKnowledge(
                userId, hypotheticalDoc, props.getChroma().getK());

        // Tag source type
        //把键 "source_type"（来源类型）的值设置为 "knowledge_base"（知识库）
        knowledgeResults.forEach(r -> r.put("source_type", "knowledge_base"));

        // Search notes
        List<Map<String, Object>> noteResults = vectorStoreService.searchNotes(
                userId, hypotheticalDoc, 3);
        noteResults.forEach(r -> r.put("source_type", "note"));

        // Merge: notes first, then knowledge base
        List<Map<String, Object>> merged = new ArrayList<>();
        merged.addAll(noteResults);
        merged.addAll(knowledgeResults);

        return merged;
    }

// 核心方法：传入用户ID和查询词，返回相关的文档列表和最终的AI总结
public Map<String, Object> getDocumentsAndSummary(String userId, String query) {
    // 1. 检索阶段：从底层（可能是向量库或Elasticsearch）获取相关文档
    List<Map<String, Object>> documents = retrieveDocuments(userId, query);

    // 2. 边界处理：如果没搜到任何东西，直接提前返回，避免浪费后续计算资源
    if (documents.isEmpty()) {
        return Map.of("documents", List.of(), "summary", "未找到相关文档。");
    }

    // 3. 数据清洗与组装：给每个文档加上[来源]标签，方便AI理解上下文
    List<String> documentContents = documents.stream()
            .map(doc -> {
                // 安全地获取来源类型，默认是 unknown
                String sourceType = (String) doc.getOrDefault("source_type", "unknown");
                // 获取标题，如果没有就用文件名，再没有就填"未知"
                String title = (String) doc.getOrDefault("title", doc.getOrDefault("filename", "未知"));
                // 获取文档正文
                String content = (String) doc.getOrDefault("content", "");
                // 根据不同来源拼接不同的前缀标签
                String tag = "note".equals(sourceType)
                        ? "[来源：笔记《" + title + "》]"
                        : "[来源：知识库《" + title + "》]";
                // 将标签和正文拼在一起返回
                return tag + "\n" + content;
            })
            .collect(Collectors.toList());

    // 4. 截断处理：为了防止文档太长撑爆大模型的Token限制，这里强行只取前3篇文档
    int maxDocs = Math.min(3, documentContents.size());
    List<String> topDocs = documentContents.subList(0, maxDocs);

    // 5. 准备大模型客户端：创建一个聊天模型实例
    ChatLanguageModel chatModel = modelFactory.createChatModel();
    
    // 6. 【关键操作】并发生成单篇摘要（Map阶段）
    List<CompletableFuture<String>> summaryFutures = topDocs.stream()
            // 为每篇文档开启一个异步线程
            .map(doc -> CompletableFuture.supplyAsync(() -> {
                try {
                    // 构造让大模型总结单篇文档的Prompt
                    String prompt = "基于以下问题和文档内容，生成简洁的摘要：\n\n问题：" + query + "\n\n文档：\n" + doc + "\n\n摘要：";
                    // 调用大模型API
                    Response<AiMessage> response = chatModel.generate(UserMessage.from(prompt));
                    return response.content().text();
                } catch (Exception e) {
                    // 兜底策略：如果大模型调用失败（如超时、限流），记录日志，并退化为直接截取原文前200个字
                    log.warn("Document summarization failed: {}", e.getMessage());
                    return doc.substring(0, Math.min(200, doc.length()));
                }
            }, taskExecutor).orTimeout(30, TimeUnit.SECONDS)) // 极为关键：设置30秒硬超时，防止线程永久卡死
            .collect(Collectors.toList());

    // 7. 阻塞等待所有并发任务完成，收集结果
    List<String> summaries = summaryFutures.stream()
            .map(f -> {
                try {
                    return f.join(); // 等待当前线程结果
                } catch (Exception e) {
                    return "摘要生成失败"; // 如果超时或异常，返回默认话术
                }
            })
            .collect(Collectors.toList());

    // 8. 【关键操作】最终的融合总结（Reduce阶段）
    String finalSummary;
    if (summaries.size() > 1) { // 如果有两篇以上摘要，需要大模型做二次融合
        try {
            // 用分隔符把多篇摘要拼接在一起
            String combinedContext = String.join("\n\n---\n\n", summaries);
            // 构造二次总结的Prompt
            String mergePrompt = "基于以下多个文档的摘要，生成一个综合性的回答：\n\n问题：" + query + "\n\n文档摘要：\n" + combinedContext + "\n\n综合回答：";
            // 再次调用大模型
            Response<AiMessage> response = chatModel.generate(UserMessage.from(mergePrompt));
            finalSummary = response.content().text();
        } catch (Exception e) {
            // 兜底策略：如果融合失败，直接把所有单篇摘要硬拼在一起返回
            finalSummary = String.join("\n\n", summaries);
        }
    } else {
        // 如果只有一篇摘要，直接作为最终结果
        finalSummary = summaries.isEmpty() ? "未找到相关文档。" : summaries.get(0);
    }

    // 9. 将原始文档和最终总结一起返回给前端
    return Map.of("documents", documents, "summary", finalSummary);
}

    public String ragSummary(String userId, String query) {
        Map<String, Object> result = getDocumentsAndSummary(userId, query);
        return (String) result.get("summary");
    }
}
