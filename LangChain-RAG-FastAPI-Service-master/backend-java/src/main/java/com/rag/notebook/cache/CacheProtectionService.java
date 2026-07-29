package com.rag.notebook.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 统一缓存防护层 —— 用一个模式同时解决缓存雪崩、穿透、击穿。
 *
 * <h3>解决的问题</h3>
 * <ul>
 *   <li><b>击穿</b>：热点 key 过期瞬间，并发请求涌入 DB。通过 Redis SETNX 互斥锁，
 *       同一时刻只有一个线程重建缓存，其余线程自旋等待。</li>
 *   <li><b>穿透</b>：查询不存在的数据（如伪造的 UUID），每次都穿透到 DB。
 *       将 null 写入缓存（短 TTL），后续相同查询直接返回 null。</li>
 *   <li><b>雪崩</b>：大量 key 在同一时刻过期，DB 压力瞬间飙升。
 *       每个 key 的 TTL 追加 ±25% 随机抖动，让过期时间自然分散。</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>
 *   Note note = cacheProtectionService.getWithProtection(
 *       "note:123", Note.class,
 *       3600L,
 *       () -> noteRepository.findById("123").orElse(null)
 *   );
 * </pre>
 */
@Slf4j
@Service
public class CacheProtectionService {

    private static final String LOCK_PREFIX = "cache:lock:";
    private static final String NULL_MARKER = "__NULL__";

    /** 锁的超时时间，防止死锁 */
    private static final long LOCK_TIMEOUT_SECONDS = 10;

    /** 自旋等待间隔 */
    private static final long SPIN_INTERVAL_MS = 50;

    /** 自旋最大次数 */
    private static final int MAX_SPIN_COUNT = 40;

    /** null 值缓存 TTL（秒），短 TTL 避免占用内存 */
    private static final long NULL_TTL_SECONDS = 60;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CacheProtectionService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 带缓存防护的读取。
     *
     * @param key      Redis 缓存 key
     * @param clazz    数据类型（用于反序列化）
     * @param baseTtl  基础 TTL（秒），实际写入会叠加随机抖动
     * @param dbFetch  数据库查询 Supplier，返回 null 表示数据不存在
     * @param <T>      数据类型
     * @return 缓存或数据库中的数据，可能为 null
     */
    public <T> T getWithProtection(String key, Class<T> clazz, long baseTtl, Supplier<T> dbFetch) {
        // 1. 先查缓存
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            if (NULL_MARKER.equals(cached)) {
                return null; // 穿透防护：缓存的 null 标记
            }
            return deserialize(cached, clazz);
        }

        // 2. 缓存未命中 → 加互斥锁重建（击穿防护）
        T data = rebuildWithLock(key, clazz, baseTtl, dbFetch);
        return data;
    }

    /**
     * 使用 Redis SETNX 互斥锁重建缓存。
     * 只有拿到锁的线程执行 DB 查询，其余自旋等待缓存就绪。
     */
    private <T> T rebuildWithLock(String key, Class<T> clazz, long baseTtl, Supplier<T> dbFetch) {
        String lockKey = LOCK_PREFIX + key;
        boolean locked = tryLock(lockKey);

        if (locked) {
            try {
                // 双重检查：拿到锁后再次读缓存（可能已被前一个线程重建）
                String cached = redisTemplate.opsForValue().get(key);
                if (cached != null) {
                    if (NULL_MARKER.equals(cached)) return null;
                    return deserialize(cached, clazz);
                }

                // 查 DB 并写入缓存
                T data = dbFetch.get();
                if (data != null) {
                    long ttl = applyJitter(baseTtl);
                    writeCache(key, data, ttl);
                } else {
                    // 穿透防护：null 也缓存，短 TTL
                    redisTemplate.opsForValue().set(key, NULL_MARKER, NULL_TTL_SECONDS, TimeUnit.SECONDS);
                }
                return data;
            } finally {
                unlock(lockKey);
            }
        } else {
            // 没拿到锁 → 自旋等待缓存就绪
            return spinWait(key, clazz);
        }
    }

    /**
     * 自旋等待，直到缓存被重建线程写入或超时放弃。
     */
    private <T> T spinWait(String key, Class<T> clazz) {
        for (int i = 0; i < MAX_SPIN_COUNT; i++) {
            try {
                Thread.sleep(SPIN_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                if (NULL_MARKER.equals(cached)) return null;
                return deserialize(cached, clazz);
            }
        }
        log.warn("Spin wait exhausted for key: {}", key);
        return null;
    }

    // ---- 辅助方法 ----

    private boolean tryLock(String lockKey) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(LOCK_TIMEOUT_SECONDS))
        );
    }

    private void unlock(String lockKey) {
        redisTemplate.delete(lockKey);
    }

    /**
     * 写入缓存（带随机 TTL 抖动，防雪崩）。
     */
    private void writeCache(String key, Object data, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(key, json, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Failed to write cache for key {}: {}", key, e.getMessage());
        }
    }

    /**
     * TTL 叠加 ±25% 随机抖动，让过期时间自然分散，避免雪崩。
     */
    static long applyJitter(long baseTtl) {
        double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.5; // 0.75 ~ 1.25
        return Math.max(1, (long) (baseTtl * jitter));
    }

    private <T> T deserialize(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.warn("Failed to deserialize cache value for class {}: {}", clazz.getSimpleName(), e.getMessage());
            return null;
        }
    }

    /** 使缓存失效（数据变更时调用） */
    public void evict(String key) {
        redisTemplate.delete(key);
    }
}
