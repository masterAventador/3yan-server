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
import java.util.UUID;

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
                .claim(TokenType.CLAIM, TokenType.ACCESS)
                .id(UUID.randomUUID().toString())
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
            Object typ = claims.get(TokenType.CLAIM);
            // 过渡期：存量旧 token 无 typ，放行；一个发布周期后收紧为 typ 必须 == ACCESS
            if (typ != null && !TokenType.ACCESS.equals(typ)) {
                throw new BusinessException(CommonErrCode.TOKEN_INVALID, "token 类型不匹配");
            }
            return Long.parseLong(claims.getSubject());
        } catch (BusinessException e) {
            throw e;
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(CommonErrCode.TOKEN_INVALID, "token 无效或已过期");
        }
    }
}
