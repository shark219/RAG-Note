package com.rag.notebook.common.auth;

import com.rag.notebook.common.exception.BusinessException;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

// 将此类标记为 Spring 的组件，交给 Spring 容器统一管理
@Component
// 实现 HandlerMethodArgumentResolver 接口，告诉 Spring 这是一个自定义的“方法参数解析器”
// 它的作用是：在请求到达 Controller 的具体方法之前，自动帮你准备好方法所需要的参数
public class UserIdArgumentResolver implements HandlerMethodArgumentResolver {

    /**
     * 第一步：判断是否支持解析当前参数
     * Spring 在处理 Controller 方法时，会遍历方法上的每一个参数，并调用这个方法来检查：
     * “这个参数归你管吗？”
     */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        // 只有当满足以下两个条件时，才返回 true（表示归这个解析器管）：
        // 1. 该参数上必须标注了自定义的 @UserId 注解
        // 2. 该参数的数据类型必须是 String 类型
        return parameter.hasParameterAnnotation(UserId.class)
                && parameter.getParameterType().equals(String.class);
    }

    /**
     * 第二步：执行真正的参数解析逻辑
     * 如果上面的 supportsParameter 返回了 true，Spring 就会调用这个方法来获取具体的参数值，
     * 并把这个返回值自动注入到 Controller 方法的参数中。
     */
    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        
        // 从 Spring Security 的全局安全上下文中获取当前的认证信息（Authentication 对象）
        // 这个上下文里的数据，正是我们之前在 JwtAuthenticationFilter 中验证 Token 成功后存进去的
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        // 防御性编程：检查认证对象是否为空，或者认证对象中的 Name（在此业务中也就是 userId）是否为空
        // 虽然有 JwtAuthenticationFilter 在前面挡着，但加上这层校验能防止系统内部逻辑漏洞或者配置遗漏
        if (auth == null || auth.getName() == null) {
            // 如果没拿到用户信息，说明处于未登录状态，直接抛出自定义的业务异常（HTTP 状态码 401，提示“未登录”）
            throw new BusinessException(401, "未登录");
        }
        
        // 如果一切正常，将获取到的 userId（auth.getName()）返回。
        // Spring 会自动把这个返回的字符串，赋值给 Controller 方法里加了 @UserId 注解的那个参数。
        return auth.getName();
    }
}
