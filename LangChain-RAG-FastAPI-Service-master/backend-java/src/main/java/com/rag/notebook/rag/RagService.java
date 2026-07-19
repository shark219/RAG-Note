package com.rag.notebook.rag;

import com.rag.notebook.agent.ModelFactory;
import com.rag.notebook.config.ApplicationProperties;
import com.rag.notebook.evaluation.entity.RagTrace;
import com.rag.notebook.evaluation.repository.RagTraceRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagService {

    private static final String SYSTEM_PROMPT =
            "你是一个智能笔记助手，请根据以下参考资料回答用户的问题。\n\n" +
            "规则：\n" +
            "1. 只基于参考资料中的内容回答，不要编造信息\n" +
            "2. 如果参考资料中没有相关信息，请回答「根据现有资料无法回答」\n" +
            "3. 如果合适，可以在回答中引用来源（如「根据你的笔记《xxx》...」）\n" +
            "4. 回答要简洁、准确、有条理";

    // 存储当前线程最新的 traceId，供 AgentService 读取
    private static final ThreadLocal<String> latestTraceId = new ThreadLocal<>();

    private final VectorStoreService vectorStoreService;
    private final HybridRetriever hybridRetriever;
    private final ModelFactory modelFactory;
    private final ApplicationProperties props;
    private final RagTraceRepository traceRepository;

    public RagService(VectorStoreService vectorStoreService, HybridRetriever hybridRetriever,
                      ModelFactory modelFactory, ApplicationProperties props,
                      RagTraceRepository traceRepository) {
        this.vectorStoreService = vectorStoreService;
        this.hybridRetriever = hybridRetriever;
        this.modelFactory = modelFactory;
        this.props = props;
        this.traceRepository = traceRepository;
    }

    /**
     * 混合检索：多Query扩展 + 向量检索 + BM25 + RRF融合
     */
    public List<Map<String, Object>> retrieveDocuments(String userId, String query) {
        // 混合检索知识库
        List<Map<String, Object>> knowledgeResults = hybridRetriever.searchKnowledge(
                userId, query, props.getChroma().getK());
        knowledgeResults.forEach(r -> r.put("source_type", "knowledge_base"));

        // 混合检索笔记
        List<Map<String, Object>> noteResults = hybridRetriever.searchNotes(
                userId, query, 3);
        noteResults.forEach(r -> r.put("source_type", "note"));

        // 合并：笔记优先，知识库在后
        List<Map<String, Object>> merged = new ArrayList<>();
        merged.addAll(noteResults);
        merged.addAll(knowledgeResults);

        return merged;
    }

    // 核心方法：传入用户ID和查询词，返回相关的文档列表和最终的AI总结
    public Map<String, Object> getDocumentsAndSummary(String userId, String query) {
        // Trace 记录开始
        RagTrace trace = new RagTrace();
        trace.setTraceId(UUID.randomUUID().toString().replace("-", ""));
        trace.setUserId(userId);
        trace.setQuery(query);
        long startTime = System.currentTimeMillis();

        // 1. 检索阶段
        long retrievalStart = System.currentTimeMillis();
        List<Map<String, Object>> documents = retrieveDocuments(userId, query);
        trace.setRetrievalLatencyMs(System.currentTimeMillis() - retrievalStart);
        trace.setRetrievedDocCount(documents.size());

        double avgSim = documents.stream()
                .mapToDouble(d -> (double) d.getOrDefault("similarity", 0.0))
                .average().orElse(0.0);
        trace.setAvgSimilarity(avgSim);

        List<String> docPreviews = documents.stream()
                .map(d -> (String) d.getOrDefault("content", ""))
                .map(c -> c.length() > 200 ? c.substring(0, 200) : c)
                .collect(Collectors.toList());
        trace.setRetrievedDocs(docPreviews);

        if (documents.isEmpty()) {
            trace.setFinalAnswer("未找到相关文档。");
            trace.setTotalLatencyMs(System.currentTimeMillis() - startTime);
            saveTrace(trace);
            return Map.of("documents", List.of(), "summary", "未找到相关文档。");
        }

        // 2. 构建参考资料（带来源标注）
        long generationStart = System.currentTimeMillis();
        String context = buildContext(documents);

        // 3. 构建用户提示词
        String userPrompt = "参考资料：\n" + context + "\n\n用户问题：" + query;

        // 4. 调用 LLM 生成回答
        String finalSummary = generateAnswer(userPrompt);

        // Trace 记录结束
        trace.setGenerationLatencyMs(System.currentTimeMillis() - generationStart);
        trace.setFinalAnswer(finalSummary);
        trace.setTotalLatencyMs(System.currentTimeMillis() - startTime);
        saveTrace(trace);

        return Map.of("documents", documents, "summary", finalSummary);
    }

    /**
     * 构建参考资料（带来源标注）
     */
    private String buildContext(List<Map<String, Object>> documents) {
        return documents.stream()
                .map(doc -> {
                    String sourceType = (String) doc.getOrDefault("source_type", "unknown");
                    String title = (String) doc.getOrDefault("title", doc.getOrDefault("filename", "未知"));
                    String content = (String) doc.getOrDefault("content", "");
                    String tag = "note".equals(sourceType)
                            ? "[来源：笔记《" + title + "》]"
                            : "[来源：知识库《" + title + "》]";
                    return tag + "\n" + content;
                })
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 调用 LLM 生成回答
     */
    private String generateAnswer(String userPrompt) {
        try {
            ChatLanguageModel chatModel = modelFactory.createChatModel();
            Response<AiMessage> response = chatModel.generate(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from(userPrompt)
            );
            return response.content().text();
        } catch (Exception e) {
            log.error("LLM 生成回答失败: {}", e.getMessage());
            return "生成回答时发生错误，请稍后重试。";
        }
    }

    public String ragSummary(String userId, String query) {
        Map<String, Object> result = getDocumentsAndSummary(userId, query);
        return (String) result.get("summary");
    }

    private void saveTrace(RagTrace trace) {
        try {
            traceRepository.save(trace);
            latestTraceId.set(trace.getTraceId());
        } catch (Exception e) {
            log.warn("Failed to save trace: {}", e.getMessage());
        }
    }

    /**
     * 获取当前线程最新的 traceId
     */
    public String getLatestTraceId() {
        return latestTraceId.get();
    }

    /**
     * 清除当前线程的 traceId
     */
    public void clearLatestTraceId() {
        latestTraceId.remove();
    }
}
