package com.rag.notebook.agent;

import dev.langchain4j.data.message.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token 计数器：估算消息列表的 token 数量
 *
 * 采用混合估算策略：
 * - 中文字符：约 1.5 token/字
 * - 英文单词：约 1.3 token/word
 * - 标点/空格：约 0.3 token/个
 *
 * 用于控制上下文窗口预算，避免超出 LLM 的最大上下文长度。
 */
@Component
public class TokenCounter {

    /** 每个中文字符的平均 token 数 */
    private static final double CN_CHAR_TOKENS = 1.5;
    /** 每个英文单词的平均 token 数 */
    private static final double EN_WORD_TOKENS = 1.3;
    /** 每条消息的固定开销（role、分隔符等） */
    private static final int MESSAGE_OVERHEAD = 4;
    /** 工具调用消息的额外开销 */
    private static final int TOOL_CALL_OVERHEAD = 20;

    /**
     * 估算文本的 token 数
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;

        int tokens = 0;
        int enWordLen = 0;

        for (char c : text.toCharArray()) {
            if (isChinese(c)) {
                // 遇到中文字符，先结算之前的英文单词
                if (enWordLen > 0) {
                    tokens += (int) Math.ceil(enWordLen * EN_WORD_TOKENS / 4.0);
                    enWordLen = 0;
                }
                tokens += CN_CHAR_TOKENS;
            } else if (isLetterOrDigit(c)) {
                enWordLen++;
            } else {
                // 空格、标点等
                if (enWordLen > 0) {
                    tokens += (int) Math.ceil(enWordLen * EN_WORD_TOKENS / 4.0);
                    enWordLen = 0;
                }
                tokens += 0.3;
            }
        }
        // 结算末尾的英文单词
        if (enWordLen > 0) {
            tokens += (int) Math.ceil(enWordLen * EN_WORD_TOKENS / 4.0);
        }

        return Math.max(1, (int) Math.ceil(tokens));
    }

    /**
     * 估算单条消息的 token 数
     */
    public int estimateMessageTokens(ChatMessage message) {
        int contentTokens = estimateTokens(message.text());
        int overhead = MESSAGE_OVERHEAD;
        // 工具调用消息有额外开销
        if (message.toString().contains("ToolExecution") || message.toString().contains("tool_call")) {
            overhead += TOOL_CALL_OVERHEAD;
        }
        return contentTokens + overhead;
    }

    /**
     * 估算消息列表的总 token 数
     */
    public int estimateTotalTokens(List<? extends ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            total += estimateMessageTokens(msg);
        }
        return total;
    }

    /**
     * 估算实体消息列表的总 token 数
     */
    public int estimateEntityTokens(List<com.rag.notebook.chat.entity.ChatMessage> messages) {
        int total = 0;
        for (com.rag.notebook.chat.entity.ChatMessage msg : messages) {
            total += estimateTokens(msg.getContent()) + MESSAGE_OVERHEAD;
        }
        return total;
    }

    private boolean isChinese(char c) {
        return c >= 0x4E00 && c <= 0x9FFF
                || c >= 0x3400 && c <= 0x4DBF
                || c >= 0x20000 && c <= 0x2A6DF
                || c >= 0xF900 && c <= 0xFAFF;
    }

    private boolean isLetterOrDigit(char c) {
        return Character.isLetterOrDigit(c);
    }
}
