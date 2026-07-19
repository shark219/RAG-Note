package com.rag.notebook.agent;

import com.rag.notebook.chat.entity.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话上下文管理器：Token 计数 + 工具结果压缩 + 摘要压缩
 *
 * 三层策略：
 * 1. Token 精确计数：用 token 数控制上下文预算，而非消息条数
 * 2. 工具结果压缩：对早期的长工具结果用 LLM 压缩，保留关键信息
 * 3. 摘要压缩：当 token 仍超预算时，将早期消息整体压缩为摘要
 */
@Slf4j
@Service
public class ContextManager {

    /** 上下文窗口的 token 预算上限（智谱GLM-4支持128K，这里用32K留余量） */
    private static final int MAX_CONTEXT_TOKENS = 32000;

    /** 工具结果压缩阈值：超过此字符数的工具结果会被压缩 */
    private static final int TOOL_RESULT_COMPRESS_THRESHOLD = 500;

    /** 压缩后保留的最大字符数 */
    private static final int COMPRESSED_MAX_CHARS = 300;

    private final TokenCounter tokenCounter;
    private final ModelFactory modelFactory;

    public ContextManager(TokenCounter tokenCounter, ModelFactory modelFactory) {
        this.tokenCounter = tokenCounter;
        this.modelFactory = modelFactory;
    }

    /**
     * 将历史消息转换为 LangChain4j 消息列表，按 token 预算进行压缩。
     *
     * @param history   全部历史消息（实体类，按时间升序）
     * @param chatModel LLM 模型实例（用于生成摘要）
     * @return LangChain4j 消息列表（可能包含压缩后的摘要消息）
     */
    public List<dev.langchain4j.data.message.ChatMessage> buildMessages(
            List<ChatMessage> history, ChatLanguageModel chatModel) {

        if (history.isEmpty()) {
            return new ArrayList<>();
        }

        // 估算当前 token 数
        int totalTokens = tokenCounter.estimateEntityTokens(history);
        log.debug("历史消息 {} 条, 估算 token: {}", history.size(), totalTokens);

        // 如果在预算内，直接转换
        if (totalTokens <= MAX_CONTEXT_TOKENS) {
            return toLcMessages(history);
        }

        log.info("历史消息 token {} 超过预算 {}, 启动上下文压缩", totalTokens, MAX_CONTEXT_TOKENS);

        // 策略1：压缩早期的长工具结果
        List<ChatMessage> processed = compressToolResults(history);

        // 重新估算
        int afterCompress = tokenCounter.estimateEntityTokens(processed);
        log.info("工具结果压缩后 token: {} → {}", totalTokens, afterCompress);

        // 策略2：如果仍然超预算，将早期消息压缩为摘要
        if (afterCompress > MAX_CONTEXT_TOKENS) {
            return compressWithSummary(processed, chatModel);
        }

        return toLcMessages(processed);
    }

    /**
     * 策略1：压缩早期消息中的长工具结果
     * 找到超长的 AI 回复（通常是工具结果），用 LLM 压缩为关键摘要
     */
    private List<ChatMessage> compressToolResults(List<ChatMessage> history) {
        List<ChatMessage> result = new ArrayList<>();

        // 只压缩前 70% 的消息，保留最近 30% 不动
        int compressBoundary = (int) (history.size() * 0.7);

        for (int i = 0; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            String content = msg.getContent();

            // 只压缩早期的、超长的 AI 回复
            if (i < compressBoundary
                    && "ai".equals(msg.getRole())
                    && content != null
                    && content.length() > TOOL_RESULT_COMPRESS_THRESHOLD) {

                String compressed = compressSingleResult(content, "工具结果");
                ChatMessage compressedMsg = new ChatMessage();
                compressedMsg.setId(msg.getId());
                compressedMsg.setSession(msg.getSession());
                compressedMsg.setRole("ai");
                compressedMsg.setContent(compressed);
                compressedMsg.setCreatedAt(msg.getCreatedAt());
                result.add(compressedMsg);
                log.debug("压缩消息 #{}: {} → {} 字", i, content.length(), compressed.length());
            } else {
                result.add(msg);
            }
        }

        return result;
    }

    /**
     * 用 LLM 压缩单条工具结果
     */
    private String compressSingleResult(String content, String toolName) {
        // 如果已经压缩过，跳过
        if (content.startsWith("[已压缩]")) {
            return content;
        }

        try {
            String template = loadPrompt("prompt/compress_tool_result.txt");
            String truncated = content.length() > 2000 ? content.substring(0, 2000) : content;
            String prompt = template
                    .replace("{tool_name}", toolName)
                    .replace("{tool_content}", truncated);

            ChatLanguageModel chatModel = modelFactory.createChatModel();
            Response<AiMessage> response = chatModel.generate(UserMessage.from(prompt));
            String compressed = response.content().text().trim();

            // 压缩后仍然太长，截断
            if (compressed.length() > COMPRESSED_MAX_CHARS) {
                compressed = compressed.substring(0, COMPRESSED_MAX_CHARS) + "...";
            }

            return "[已压缩] " + compressed;
        } catch (Exception e) {
            log.warn("工具结果压缩失败，使用截断: {}", e.getMessage());
            return content.substring(0, Math.min(COMPRESSED_MAX_CHARS, content.length())) + "...";
        }
    }

    /**
     * 策略2：将早期消息压缩为摘要，保留最近消息原文
     */
    private List<dev.langchain4j.data.message.ChatMessage> compressWithSummary(
            List<ChatMessage> history, ChatLanguageModel chatModel) {

        // 从前往后累加 token，找到截断点：保留最近的消息使得总 token 在预算内
        int recentStart = history.size();
        int tokenBudget = MAX_CONTEXT_TOKENS;

        // 从后往前累加，直到预算用完
        for (int i = history.size() - 1; i >= 0; i--) {
            int msgTokens = tokenCounter.estimateTokens(history.get(i).getContent()) + 4;
            if (tokenBudget - msgTokens < 0) {
                recentStart = i + 1;
                break;
            }
            tokenBudget -= msgTokens;
            recentStart = i;
        }

        // 至少保留最近 5 条消息
        recentStart = Math.max(recentStart, history.size() - 5);
        // 最多压缩前 80% 的消息
        recentStart = Math.min(recentStart, (int) (history.size() * 0.8));

        List<ChatMessage> earlyMessages = new ArrayList<>(history.subList(0, recentStart));
        List<ChatMessage> recentMessages = new ArrayList<>(history.subList(recentStart, history.size()));

        log.info("摘要压缩: 前 {} 条消息 → 摘要, 保留最近 {} 条原文", earlyMessages.size(), recentMessages.size());

        String summary = summarize(earlyMessages, chatModel);

        List<dev.langchain4j.data.message.ChatMessage> result = new ArrayList<>();
        result.add(SystemMessage.from("[之前的对话摘要] " + summary));
        result.addAll(toLcMessages(recentMessages));
        return result;
    }

    /**
     * 用 LLM 对早期消息生成摘要
     */
    private String summarize(List<ChatMessage> messages, ChatLanguageModel chatModel) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            String role = "human".equals(msg.getRole()) ? "用户" : "AI";
            String content = msg.getContent();
            // 截断超长内容
            if (content != null && content.length() > 300) {
                content = content.substring(0, 300) + "...";
            }
            sb.append(role).append(": ").append(content).append("\n");
        }

        String prompt = "请将以下对话历史压缩为一段简洁的摘要，保留关键信息和用户的核心诉求，不要遗漏重要结论：\n\n"
                + sb + "\n摘要：";

        try {
            Response<AiMessage> response = chatModel.generate(UserMessage.from(prompt));
            return response.content().text();
        } catch (Exception e) {
            log.warn("摘要生成失败，使用兜底摘要: {}", e.getMessage());
            return "用户与AI进行了" + messages.size() + "轮对话，讨论了多个话题。";
        }
    }

    private List<dev.langchain4j.data.message.ChatMessage> toLcMessages(List<ChatMessage> history) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        for (ChatMessage msg : history) {
            if ("human".equals(msg.getRole())) {
                messages.add(UserMessage.from(msg.getContent()));
            } else if ("ai".equals(msg.getRole())) {
                messages.add(AiMessage.from(msg.getContent()));
            }
        }
        return messages;
    }

    private String loadPrompt(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new RuntimeException("加载 prompt 失败: " + path, e);
        }
    }
}
