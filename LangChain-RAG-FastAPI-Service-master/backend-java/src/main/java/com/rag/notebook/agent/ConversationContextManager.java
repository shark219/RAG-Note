package com.rag.notebook.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理每个 session 的对话上下文（当前正在操作的笔记）。
 *
 * 存储：ConcurrentHashMap（内存），不持久化。
 * 每个 session 的生命周期内，"这篇笔记"、"给它xxx"等引用能正确解析。
 */
@Slf4j
@Component
public class ConversationContextManager {

    private final ConcurrentHashMap<String, ConversationContext> contexts = new ConcurrentHashMap<>();

    /**
     * 获取指定 session 的上下文，没有则创建
     */
    public ConversationContext getOrCreate(String sessionId) {
        return contexts.computeIfAbsent(sessionId, k -> new ConversationContext());
    }

    /**
     * 获取指定 session 的上下文，可能返回 null
     */
    public ConversationContext get(String sessionId) {
        return contexts.get(sessionId);
    }

    /**
     * 更新上下文
     */
    public void update(String sessionId, ConversationContext ctx) {
        contexts.put(sessionId, ctx);
    }

    /**
     * 清除指定 session 的上下文
     */
    public void clear(String sessionId) {
        contexts.remove(sessionId);
        log.debug("清除 session {} 的上下文", sessionId);
    }

    /**
     * 解析用户查询中的引用。
     * 如果 query 包含代词引用且有活跃笔记上下文，自动注入 noteId。
     *
     * @return 解析后的 query（可能附加了上下文信息），如果没有可解析的引用则返回原 query
     */
    public String resolveReferences(String query, String sessionId) {
        ConversationContext ctx = get(sessionId);
        if (ctx == null || !ctx.hasActiveNote()) return query;

        // 检测引用词
        boolean hasReference = query.contains("这篇") || query.contains("那个笔记")
                || query.contains("它") || query.contains("刚才那篇")
                || query.contains("这个笔记") || query.contains("那篇笔记")
                || query.contains("笔记里") || query.contains("笔记里面");

        if (!hasReference) return query;

        String hint = ctx.toContextHint();
        if (hint != null) {
            log.info("上下文解析: session={}, 注入当前笔记《{}》({})", sessionId, ctx.getCurrentNoteTitle(), ctx.getCurrentNoteId());
            return query + "\n\n" + hint;
        }
        return query;
    }
}
