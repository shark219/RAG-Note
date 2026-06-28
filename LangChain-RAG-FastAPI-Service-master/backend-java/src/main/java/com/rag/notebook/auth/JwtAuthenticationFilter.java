package com.rag.notebook.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;


// 使用 Lombok 注解，自动生成一个名为 log 的日志对象，用于打印日志
@Slf4j
// 将此类标记为 Spring 的一个组件（Bean），使其能够被 Spring 容器扫描并管理
@Component
    // 继承 OncePerRequestFilter，确保该过滤器在每次 HTTP 请求时只会被执行一次
    public class JwtAuthenticationFilter extends OncePerRequestFilter {
    // 声明一个不可变的 JwtService 实例，用于处理 JWT 相关的逻辑（如验证、解析等）
    private final JwtService jwtService;

    // 构造函数注入，Spring 在实例化这个过滤器时会自动传入 JwtService 的实现
    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    // 重写父类的核心过滤方法，所有经过此过滤器的请求都会执行这里的逻辑
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        
        // 从 HTTP 请求头中获取名为 "Authorization" 的内容
        String authHeader = request.getHeader("Authorization");

        // 检查请求头是否为空，或者是否不是以 "Bearer " 字符串开头（标准的 JWT 传递格式）
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // 如果没有合法的 Authorization 请求头，直接放行，交由后续的过滤器处理（通常后续会有安全拦截器拦截未登录请求）
            filterChain.doFilter(request, response);
            // 结束当前方法的执行，不再向下执行解析 token 的逻辑
            return;
        }

        // 截取请求头前 7 个字符（即 "Bearer "）之后的部分，获取真正的 JWT token 字符串
        String token = authHeader.substring(7);

        // 使用 try-catch 块捕获在解析或验证 token 过程中可能出现的任何异常（如 token 过期、格式错误等）
        try {
            // 调用 jwtService 验证这个 token 是否合法且未过期
            if (jwtService.isTokenValid(token)) {
                // 如果 token 有效，从 token 中解析出用户的 ID（或用户名）
                String userId = jwtService.getUserIdFromToken(token);
                
                // 创建一个 Spring Security 的认证令牌对象，包含用户 ID，密码设为 null（因为是 token 登录不需要密码），以及权限列表（此处为空列表）
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList());
                
                // 将当前 HTTP 请求的一些详细信息（如 IP 地址、Session ID 等）封装并设置到认证令牌中
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                
                // 将构建好的认证令牌存入 Spring Security 的安全上下文中，代表该请求已被成功认证，标记用户为已登录状态
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        } catch (Exception e) {
            // 如果上述过程中发生任何异常，记录一条 debug 级别的日志，输出异常信息
            log.debug("JWT authentication failed: {}", e.getMessage());
        }

        // 无论 token 解析成功与否（或者是异常被捕获），都让请求继续向下流转，交给过滤链中的下一个过滤器或最终的目标接口处理
        filterChain.doFilter(request, response);
    }
}