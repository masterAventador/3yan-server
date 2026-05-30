package com.sanyan.common.auth;

import com.sanyan.common.error.BusinessException;
import com.sanyan.common.error.CommonErrCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.*;

class JwtUtilTest {

    private static final String SECRET = "test-secret-key-at-least-256-bits-long-for-hmac-sha";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 30);
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

    @Test
    void generateToken_carriesAccessTypAndJti() {
        String token = jwtUtil.generateToken(42L);

        Claims claims = Jwts.parser()
                .verifyWith(KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertThat(claims.get(TokenType.CLAIM)).isEqualTo(TokenType.ACCESS);
        assertThat(claims.getId()).isNotBlank();
    }

    @Test
    void parseUserId_rejectsBindTypToken() {
        Date now = new Date();
        String bindToken = Jwts.builder()
                .subject("42")
                .claim(TokenType.CLAIM, TokenType.BIND)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 60000L))
                .signWith(KEY)
                .compact();

        assertThatThrownBy(() -> jwtUtil.parseUserId(bindToken))
                .isInstanceOf(BusinessException.class)
                .extracting("errCode").isEqualTo(CommonErrCode.TOKEN_INVALID);
    }

    @Test
    void parseUserId_acceptsLegacyTokenWithoutTyp() {
        Date now = new Date();
        String legacyToken = Jwts.builder()
                .subject("42")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 60000L))
                .signWith(KEY)
                .compact();

        assertThat(jwtUtil.parseUserId(legacyToken)).isEqualTo(42L);
    }
}
