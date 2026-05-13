package com.sanyan.embedding.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 校验 {@code X-Internal-Token} 请求头是否与配置 {@code sanyan.embedding.internal-token} 相等。
 *
 * <p>不通过场景：
 * <ul>
 *   <li>未配置 token（运维问题）→ 500（避免任何人不带 header 都能调通）</li>
 *   <li>header 缺失 / 不匹配 → 401</li>
 * </ul>
 *
 * <p>设计取舍：本拦截器**直接写状态码 + 短文本**，不抛 {@link com.sanyan.common.error.BusinessException}
 * 走 {@code GlobalExceptionHandler}——HTTP 拦截器在 DispatcherServlet 之前执行，
 * 401 用裸状态码更符合 HTTP 拦截语义；body 留空，避免泄露内部细节。
 */
@Slf4j
@Component
public class InternalTokenInterceptor implements HandlerInterceptor {

    static final String TOKEN_HEADER = "X-Internal-Token";

    private final String configuredToken;

    public InternalTokenInterceptor(
            @Value("${sanyan.embedding.internal-token:}") String configuredToken) {
        this.configuredToken = configuredToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        if (configuredToken == null || configuredToken.isBlank()) {
            log.error("sanyan.embedding.internal-token 未配置；拒绝所有请求");
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("Embedding internal token not configured");
            return false;
        }

        String header = request.getHeader(TOKEN_HEADER);
        if (header == null || !configuredToken.equals(header)) {
            log.warn("X-Internal-Token 校验失败：headerPresent={}", header != null);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        return true;
    }
}
