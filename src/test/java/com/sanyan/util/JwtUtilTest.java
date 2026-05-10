package com.sanyan.util;

import com.sanyan.exception.InvalidTokenException;
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
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void shouldRejectEmptyToken() {
        assertThatThrownBy(() -> jwtUtil.parseUserId(""))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void shouldRejectBlankToken() {
        assertThatThrownBy(() -> jwtUtil.parseUserId("   "))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void shouldRejectInvalidToken() {
        assertThatThrownBy(() -> jwtUtil.parseUserId("invalid.token.here"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void shouldRejectExpiredToken() {
        JwtUtil shortLived = new JwtUtil("test-secret-key-at-least-256-bits-long-for-hmac-sha", 0);
        String token = shortLived.generateToken(1L);

        assertThatThrownBy(() -> shortLived.parseUserId(token))
                .isInstanceOf(InvalidTokenException.class);
    }
}
