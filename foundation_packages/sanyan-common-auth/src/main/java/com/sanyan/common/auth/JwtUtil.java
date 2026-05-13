package com.sanyan.common.auth;

import com.sanyan.common.error.BusinessException;
import com.sanyan.common.error.CommonErrCode;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expirationMs;

    public JwtUtil(
            @Value("${sanyan.jwt.secret}") String secret,
            @Value("${sanyan.jwt.expiration-days}") int expirationDays) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationDays * 86400000L;
    }

    public String generateToken(Long userId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    public Long parseUserId(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(CommonErrCode.TOKEN_INVALID, "token 为空");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Long.parseLong(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(CommonErrCode.TOKEN_INVALID, "token 无效或已过期");
        }
    }
}
