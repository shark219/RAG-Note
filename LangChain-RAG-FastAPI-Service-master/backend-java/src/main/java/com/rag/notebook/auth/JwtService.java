package com.rag.notebook.auth;

import com.rag.notebook.config.ApplicationProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class JwtService {

    private final ApplicationProperties props;
    private final StringRedisTemplate redisTemplate;

    public JwtService(ApplicationProperties props, StringRedisTemplate redisTemplate) {
        this.props = props;
        this.redisTemplate = redisTemplate;
    }

    public String generateToken(String userId, String username, String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("user_id", userId);
        claims.put("username", username);
        claims.put("email", email);

        Date now = new Date();
        Date expiry = new Date(now.getTime() + props.getJwt().getExpiration());

        return Jwts.builder()
                .claims(claims)
                .subject(userId)
                .issuedAt(now)
                .expiration(expiry)
                .id(UUID.randomUUID().toString())
                .signWith(getSigningKey())
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = parseToken(token);
            String jti = claims.getId();
            if (jti != null && isTokenBlacklisted(jti)) {
                return false;
            }
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    public String getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        Object userId = claims.get("user_id");
        return userId != null ? userId.toString() : claims.getSubject();
    }

    public String getJtiFromToken(String token) {
        return parseToken(token).getId();
    }

    public long getExpirationFromToken(String token) {
        Date exp = parseToken(token).getExpiration();
        return exp.getTime() - System.currentTimeMillis();
    }

    // 定义一个将 Token 加入黑名单的方法
    // 参数 jti：JWT ID，也就是这个 Token 的唯一编号
    // 参数 ttlMillis：这个 Token 距离真正过期还剩下的时间（毫秒）
    public void blacklistToken(String jti, long ttlMillis) {
        
        // 拼接一个用于存入 Redis 的键名（Key）
        // 加上 "blacklist:" 前缀是一种好习惯，能防止与 Redis 中其他业务的数据发生冲突
        String key = "blacklist:" + jti;
        
        // 调用 Redis 模板，将这个 key 存入 Redis 中
        // 1. 值设为 "1"（其实存什么都行，我们只关心这个 key 存不存在）
        // 2. 将这个 key 在 Redis 中的存活时间（过期时间）设置为 ttlMillis 毫秒
        redisTemplate.opsForValue().set(key, "1", ttlMillis, TimeUnit.MILLISECONDS);
        
        // 记录一条 debug 日志，方便开发和排错，说明这个 Token 已经被成功拉黑
        log.debug("Token blacklisted: {}", jti);
    }   

    public boolean isTokenBlacklisted(String jti) {
        String key = "blacklist:" + jti;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    private SecretKey getSigningKey() {
        String secret = props.getJwt().getSecret();
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
