package com.sanyan.util;

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
    void shouldRejectInvalidToken() {
        assertThatThrownBy(() -> jwtUtil.parseUserId("invalid.token.here"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldRejectExpiredToken() {
        JwtUtil shortLived = new JwtUtil("test-secret-key-at-least-256-bits-long-for-hmac-sha", 0);
        String token = shortLived.generateToken(1L);

        assertThatThrownBy(() -> shortLived.parseUserId(token))
                .isInstanceOf(RuntimeException.class);
    }
}
