package com.rag.notebook.health;

import com.rag.notebook.common.result.ApiResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

@RestController
@RequestMapping("/health")
public class HealthController {

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;

    public HealthController(DataSource dataSource, StringRedisTemplate redisTemplate) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/live")
    public ApiResponse<Map<String, String>> liveness() {
        return ApiResponse.success(Map.of("status", "UP"));
    }

    @GetMapping("/ready")
    public ApiResponse<Map<String, Object>> readiness() {
        boolean mysqlOk = checkMysql();
        boolean redisOk = checkRedis();

        Map<String, Object> details = Map.of(
                "mysql", mysqlOk ? "UP" : "DOWN",
                "redis", redisOk ? "UP" : "DOWN",
                "status", (mysqlOk && redisOk) ? "UP" : "DOWN"
        );

        if (mysqlOk && redisOk) {
            return ApiResponse.success(details);
        }
        return ApiResponse.error(503, "Service unavailable", details);
    }

    private boolean checkMysql() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(3);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkRedis() {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey("__health_check__"));
        } catch (Exception e) {
            try {
                redisTemplate.opsForValue().set("__health_check__", "1");
                redisTemplate.delete("__health_check__");
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }
}
