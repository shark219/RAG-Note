package com.rag.notebook.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 应用层查询结果缓存（Supervisor 规划、Query 扩展等 LLM 调用结果）。
 * 不是 Anthropic 语义的 Prompt Cache（KV 复用），而是完整的业务结果缓存。
 */
@Slf4j
@Service
public class QueryCacheService {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public QueryCacheService(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public <T> T get(String key, TypeReference<T> type) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? null : mapper.readValue(value, type);
        } catch (Exception e) {
            log.debug("Query cache read failed: {}", e.getMessage());
            return null;
        }
    }

    public <T> T getOrLoad(String key, TypeReference<T> type, Duration ttl, Supplier<T> loader) {
        T cached = get(key, type);
        if (cached != null) return cached;
        T value = loader.get();
        if (value != null) put(key, value, ttl);
        return value;
    }

    public void put(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.debug("Query cache write failed: {}", e.getMessage());
        }
    }

    public String hashKey(String prefix, String... parts) {
        try {
            String raw = String.join("|", parts);
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return prefix + ":" + java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return prefix + ":fallback:" + String.join("|", parts).hashCode();
        }
    }
}
