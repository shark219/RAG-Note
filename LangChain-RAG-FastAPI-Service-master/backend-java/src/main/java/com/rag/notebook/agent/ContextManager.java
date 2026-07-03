package com.rag.notebook.agent;

import com.rag.notebook.chat.entity.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话上下文管理器：滑动窗口 + 摘要压缩
 *
 * 当历史消息超过阈值时，将早期消息压缩为摘要，仅保留近期消息原文，
 * 控制发给 LLM 的消息长度，避免超出上下文窗口限制。
 */
@Slf4j
@Service
public class ContextManager {

    /** 滑动窗口大小：保留最近 N 条消息的原文 */
    private static final int MAX_RECENT_MESSAGES = 20;

    /** 摘要触发阈值：历史消息超过此数量时触发压缩 */
    private static final int SUMMARIZE_THRESHOLD = 30;

    /**
     * 将历史消息转换为 LangChain4j 消息列表，超过阈值时做摘要压缩。
     *
     * @param history   全部历史消息（实体类，按时间升序）
     * @param chatModel LLM 模型实例（用于生成摘要）
     * @return LangChain4j 消息列表（可能包含摘要 SystemMessage）
     */
    public List<dev.langchain4j.data.message.ChatMessage> buildMessages(
            List<ChatMessage> history, ChatLanguageModel chatModel) {

        if (history.size() <= SUMMARIZE_THRESHOLD) {
            // 不需要压缩，直接转换
            return toLcMessages(history);
        }

        log.info("历史消息 {} 条，超过阈值 {}，触发摘要压缩，保留最近 {} 条原文",
                history.size(), SUMMARIZE_THRESHOLD, MAX_RECENT_MESSAGES);

        // 分离：早期消息（需要压缩）+ 近期消息（保留原文）
        List<ChatMessage> earlyMessages = new ArrayList<>(
                history.subList(0, history.size() - MAX_RECENT_MESSAGES));
        List<ChatMessage> recentMessages = new ArrayList<>(
                history.subList(history.size() - MAX_RECENT_MESSAGES, history.size()));

        // 对早期消息生成摘要
        String summary = summarize(earlyMessages, chatModel);
        log.info("摘要压缩完成，早期 {} 条消息压缩为 {} 字摘要", earlyMessages.size(), summary.length());

        // 构建：摘要 + 近期消息
        List<dev.langchain4j.data.message.ChatMessage> result = new ArrayList<>();
        result.add(SystemMessage.from("[之前的对话摘要] " + summary));
        result.addAll(toLcMessages(recentMessages));
        return result;
    }

    /**
     * 将实体 ChatMessage 列表转换为 LangChain4j 消息列表
     */
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

    /**
     * 用 LLM 对早期消息生成摘要
     */
    private String summarize(List<ChatMessage> messages, ChatLanguageModel chatModel) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            String role = "human".equals(msg.getRole()) ? "用户" : "AI";
            sb.append(role).append(": ").append(truncate(msg.getContent(), 200)).append("\n");
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

    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}
