package com.rag.notebook.common.exception;

import com.rag.notebook.common.result.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.stream.Collectors;

// Lombok 注解：自动生成 log 对象，用于打印日志
@Slf4j
// Spring 核心注解：声明这是一个全局的增强型控制器拦截器，它会自动拦截所有 @RestController 抛出的异常
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==========================================
    // 1. 自定义业务异常兜底
    // ==========================================
    // 拦截我们自己抛出的 BusinessException（比如上文中“未登录”抛出的异常）
    @ExceptionHandler(BusinessException.class)
    // 注意这里：HTTP 状态码强制返回 200 OK。
    // 这是一种国内非常主流的架构规范：只要服务器没死，HTTP 状态全是 200，真正的错误类型放在 JSON 体（ApiResponse）的 code 里
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleBusinessException(BusinessException ex) {
        // 业务异常属于正常逻辑分支，只打 warn 警告日志即可，不需要打印长篇大论的堆栈
        log.warn("Business exception: {}", ex.getMessage());
        // 将异常中携带的错误码和错误信息，包装成统一的 ApiResponse 格式返回给前端
        return ApiResponse.error(ex.getCode(), ex.getMessage());
    }

    // ==========================================
    // 2. 认证异常（Spring Security）
    // ==========================================
    // 拦截登录时抛出的用户名/密码错误异常
    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleBadCredentials(BadCredentialsException ex) {
        // 直接返回前端能看懂的提示语，屏蔽底层异常细节
        return ApiResponse.error(400, "用户名或密码错误");
    }


    
    // ==========================================
    // 3. 参数校验异常（JSR 303/Hibernate Validator）
    // ==========================================
    // 当实体类加上了 @Valid 或 @Validated，且参数不合法时（比如邮箱格式错、非空字段为空），会抛出此异常
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST) // HTTP 状态码设为 400
    public ApiResponse<Object> handleValidation(MethodArgumentNotValidException ex) {
        // 这是一段精彩的 Java 8 Stream 连招：
        // 提取所有出错的字段 -> 拿到咱们写在注解里的 defaultMessage（比如"密码长度不能小于8"）-> 用逗号把它们拼成一句话
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return ApiResponse.error(400, message, null);
    }

    // ==========================================
    // 4. 数据库层级异常兜底
    // ==========================================
    // 拦截 JPA/MyBatis 在操作数据库时抛出的约束冲突异常
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIntegrityViolation(DataIntegrityViolationException ex) {
        String msg = ex.getMessage();
        if (msg != null) {
            // 通过解析底层的 SQL 报错信息来给前端反馈。
            // 比如你在数据库给 username 设了唯一索引，又存了一个同名的，就会触发 Duplicate entry
            if (msg.contains("Duplicate entry")) {
                return ApiResponse.error(400, "数据已存在，请勿重复提交");
            }
            // 比如你要删一个用户，但是他名下还有订单（外键约束），就会触发 FOREIGN KEY
            if (msg.contains("FOREIGN KEY")) {
                return ApiResponse.error(400, "关联数据不存在");
            }
        }
        // 兜底提示
        return ApiResponse.error(400, "数据完整性约束违反");
    }

    // ==========================================
    // 5. 常见 HTTP 错误
    // ==========================================
    // 拦截 404 错误（资源/接口找不到）
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound(NoResourceFoundException ex) {
        return ApiResponse.error(404, "请求的资源不存在");
    }

    // 拦截 403 错误（用户登录了，但想干一件越权的事，被 Spring Security 拦下）
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleAccessDenied(AccessDeniedException ex) {
        return ApiResponse.error(403, "没有权限访问该资源");
    }

    // 拦截 JSON 解析错误（比如前端发来的 JSON 缺了个逗号，或者把字符串传给了 Integer 字段）
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleNotReadable(HttpMessageNotReadableException ex) {
        return ApiResponse.error(400, "请求体格式错误");
    }

    // ==========================================
    // 6. 终极兜底（Uncaught Exceptions）
    // ==========================================
    // 拦截所有前面没能处理的异常（比如传说中的 NullPointerException 空指针）
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR) // HTTP 状态码 500
    public ApiResponse<Void> handleGeneral(Exception ex, HttpServletRequest request) {
        // 这是真正不可预知的系统 Bug，必须打 error 级别日志，并且把具体的请求路径和完整堆栈（ex）记录下来，方便程序员第二天看日志修 Bug
        log.error("Unhandled exception on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        // 对外（前端和用户）永远只显示一句温柔的“服务器内部错误”，绝不把带有代码行数的红色报错发给外面看（防黑客分析代码结构）
        return ApiResponse.error(500, "服务器内部错误");
    }
}
