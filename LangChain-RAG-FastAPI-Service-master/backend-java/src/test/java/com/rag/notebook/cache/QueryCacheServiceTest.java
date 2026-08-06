package com.rag.notebook.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueryCacheServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> ops;

    private final ObjectMapper mapper = new ObjectMapper();
    private QueryCacheService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(ops);
        service = new QueryCacheService(redisTemplate, mapper);
    }

    @Test
    void getReturnsNullOnCacheMiss() {
        when(ops.get("key-1")).thenReturn(null);
        assertNull(service.get("key-1", new TypeReference<String>() {}));
    }

    @Test
    void getReturnsDeserializedValueOnCacheHit() throws Exception {
        when(ops.get("key-2")).thenReturn("\"hello\"");
        assertEquals("hello", service.get("key-2", new TypeReference<String>() {}));
    }

    @Test
    void getReturnsNullOnRedisException() {
        when(ops.get("key-err")).thenThrow(new RuntimeException("connection timeout"));
        assertNull(service.get("key-err", new TypeReference<String>() {}));
    }

    @Test
    void getOrLoadReturnsCachedValueWithoutInvokingLoader() {
        when(ops.get("key-3")).thenReturn("\"cached\"");
        String result = service.getOrLoad("key-3", new TypeReference<String>() {},
                Duration.ofSeconds(10), () -> { fail("loader should not be called"); return "fresh"; });
        assertEquals("cached", result);
    }

    @Test
    void getOrLoadInvokesLoaderOnCacheMissAndStoresResult() {
        when(ops.get("key-4")).thenReturn(null);
        String result = service.getOrLoad("key-4", new TypeReference<String>() {},
                Duration.ofSeconds(10), () -> "fresh");
        assertEquals("fresh", result);
        verify(ops).set(eq("key-4"), anyString(), eq(Duration.ofSeconds(10)));
    }

    @Test
    void putSucceedsSilently() {
        service.put("key-5", List.of("a", "b"), Duration.ofSeconds(30));
        verify(ops).set(eq("key-5"), anyString(), eq(Duration.ofSeconds(30)));
    }

    @Test
    void putSwallowsRedisException() {
        doThrow(new RuntimeException("write error")).when(ops).set(anyString(), anyString(), any());
        assertDoesNotThrow(() -> service.put("key-6", "data", Duration.ofSeconds(10)));
    }

    @Test
    void hashKeyProducesStableOutput() {
        String k1 = service.hashKey("prefix", "a", "b", "c");
        String k2 = service.hashKey("prefix", "a", "b", "c");
        assertEquals(k1, k2);
        assertTrue(k1.startsWith("prefix:"));
    }

    @Test
    void getReturnsNullForCorruptJson() {
        when(ops.get("corrupt")).thenReturn("{not-json");
        assertNull(service.get("corrupt", new TypeReference<List<String>>() {}));
    }
}
