package com.rag.notebook.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class RedisCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public String getString(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    public <T> T getJson(String key, Class<T> clazz) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) return null;
        try {
            return objectMapper.readValue(value, clazz);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize Redis cache for key {}: {}", key, e.getMessage());
            return null;
        }
    }

    public void set(String key, String value, long ttlSeconds) {
        redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
    }

    public void setJson(String key, Object value, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, ttlSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize value for Redis cache key {}: {}", key, e.getMessage());
        }
    }

    public void delete(String key) {
        redisTemplate.delete(key);
    }

    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public void deleteUserCache(String userId) {
        String key = "user:" + userId;
        delete(key);
    }
}
