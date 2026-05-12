package com.sanyan.common.auth;

import com.sanyan.common.error.BusinessException;
import com.sanyan.common.error.CommonErrCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil("test-secret-key-at-least-256-bits-long-for-hmac-sha", 30);
    }

    @Test
    void shouldGenerateAndParseToken() {
        String token = jwtUtil.generateToken(42L);
        assertThat(token).isNotBlank();

        Long userId = jwtUtil.parseUserId(token);
        assertThat(userId).isEqualTo(42L);
    }

    @Test
    void shouldRejectNullToken() {
        assertThatThrownBy(() -> jwtUtil.parseUserId(null))
                .isInstanceOf(BusinessException.class)
                .extracting("errCode").isEqualTo(CommonErrCode.TOKEN_INVALID);
    }

    @Test
    void shouldRejectEmptyToken() {
        assertThatThrownBy(() -> jwtUtil.parseUserId(""))
                .isInstanceOf(BusinessException.class)
                .extracting("errCode").isEqualTo(CommonErrCode.TOKEN_INVALID);
    }

    @Test
    void shouldRejectBlankToken() {
        assertThatThrownBy(() -> jwtUtil.parseUserId("   "))
                .isInstanceOf(BusinessException.class)
                .extracting("errCode").isEqualTo(CommonErrCode.TOKEN_INVALID);
    }

    @Test
    void shouldRejectInvalidToken() {
        assertThatThrownBy(() -> jwtUtil.parseUserId("invalid.token.here"))
                .isInstanceOf(BusinessException.class)
                .extracting("errCode").isEqualTo(CommonErrCode.TOKEN_INVALID);
    }

    @Test
    void shouldRejectExpiredToken() {
        JwtUtil shortLived = new JwtUtil("test-secret-key-at-least-256-bits-long-for-hmac-sha", 0);
        String token = shortLived.generateToken(1L);

        assertThatThrownBy(() -> shortLived.parseUserId(token))
                .isInstanceOf(BusinessException.class)
                .extracting("errCode").isEqualTo(CommonErrCode.TOKEN_INVALID);
    }
}
