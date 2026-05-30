package com.sanyan.common.auth;

import com.sanyan.common.error.BusinessException;
import com.sanyan.common.error.CommonErrCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * 第三方登录"未绑账号"时签发的短期 bindTicket 工具。
 * 与登录 JWT 强隔离：独立 HMAC 密钥（≠ jwt.secret）、typ=BIND、带 jti（一次性消费用）、默认 10min TTL。
 * bindTicket 不含 subject（签发时尚无 user）。
 */
@Component
public class BindTicketUtil {

    private static final String CLAIM_PROVIDER = "provider";
    private static final String CLAIM_EXTERNAL_ID = "externalId";
    private static final String CLAIM_RAW_OPENID = "rawOpenid";

    private final SecretKey key;
    private final long ttlMs;

    public BindTicketUtil(
            @Value("${sanyan.oauth.bind-ticket.secret}") String secret,
            @Value("${sanyan.oauth.bind-ticket.ttl-minutes:10}") int ttlMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMs = ttlMinutes * 60000L;
    }

    public String issue(String provider, String externalId, String rawOpenid) {
        String jti = UUID.randomUUID().toString();
        Date now = new Date();
        return Jwts.builder()
                .claim(CLAIM_PROVIDER, provider)
                .claim(CLAIM_EXTERNAL_ID, externalId)
                .claim(CLAIM_RAW_OPENID, rawOpenid)
                .claim(TokenType.CLAIM, TokenType.BIND)
                .id(jti)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMs))
                .signWith(key)
                .compact();
    }

    public BindTicketPayload parse(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new BusinessException(CommonErrCode.TOKEN_INVALID, "bindTicket 为空");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(ticket)
                    .getPayload();
            if (!TokenType.BIND.equals(claims.get(TokenType.CLAIM))) {
                throw new BusinessException(CommonErrCode.TOKEN_INVALID, "bindTicket 类型不匹配");
            }
            return new BindTicketPayload(
                    claims.get(CLAIM_PROVIDER, String.class),
                    claims.get(CLAIM_EXTERNAL_ID, String.class),
                    claims.get(CLAIM_RAW_OPENID, String.class),
                    claims.getId());
        } catch (BusinessException e) {
            throw e;
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(CommonErrCode.TOKEN_INVALID, "bindTicket 无效或已过期");
        }
    }
}
