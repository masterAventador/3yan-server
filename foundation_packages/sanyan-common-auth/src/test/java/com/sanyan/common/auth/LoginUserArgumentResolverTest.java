package com.sanyan.common.auth;

import com.sanyan.common.error.BusinessException;
import com.sanyan.common.error.CommonErrCode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.context.request.NativeWebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * LoginUserArgumentResolver 纯 Mockito 单测，只验证 resolveArgument 行为本身。
 * "BusinessException → BaseResp.failed" 的映射由 common-web 的 GlobalExceptionHandlerTest 单独覆盖。
 */
@ExtendWith(MockitoExtension.class)
class LoginUserArgumentResolverTest {

    private static final String SECRET = "test-secret-key-at-least-256-bits-long-for-hmac-sha";

    @Mock private NativeWebRequest webRequest;
    @Mock private HttpServletRequest httpRequest;

    private JwtUtil jwtUtil;
    private LoginUserArgumentResolver resolver;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 30);
        resolver = new LoginUserArgumentResolver(jwtUtil);
    }

    @Test
    void resolveArgument_shouldReturnUserIdForValidBearerToken() {
        String token = jwtUtil.generateToken(99L);
        when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
        when(httpRequest.getHeader("Authorization")).thenReturn("Bearer " + token);

        Object result = resolver.resolveArgument(null, null, webRequest, null);

        assertThat(result).isEqualTo(99L);
    }

    @Test
    void resolveArgument_shouldReturnUserIdWhenTokenHasNoBearerPrefix() {
        String token = jwtUtil.generateToken(7L);
        when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
        when(httpRequest.getHeader("Authorization")).thenReturn(token);

        Object result = resolver.resolveArgument(null, null, webRequest, null);

        assertThat(result).isEqualTo(7L);
    }

    @Test
    void resolveArgument_shouldThrowBusinessExceptionWhenAuthorizationHeaderMissing() {
        when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
        when(httpRequest.getHeader("Authorization")).thenReturn(null);

        assertThatThrownBy(() -> resolver.resolveArgument(null, null, webRequest, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(CommonErrCode.TOKEN_INVALID);
    }

    @Test
    void resolveArgument_shouldThrowBusinessExceptionWhenTokenInvalid() {
        when(webRequest.getNativeRequest(HttpServletRequest.class)).thenReturn(httpRequest);
        when(httpRequest.getHeader("Authorization")).thenReturn("Bearer not.a.real.token");

        assertThatThrownBy(() -> resolver.resolveArgument(null, null, webRequest, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(CommonErrCode.TOKEN_INVALID);
    }
}
