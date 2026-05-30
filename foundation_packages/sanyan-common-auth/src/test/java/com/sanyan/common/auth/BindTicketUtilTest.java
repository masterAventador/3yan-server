package com.sanyan.common.auth;

import com.sanyan.common.error.BusinessException;
import com.sanyan.common.error.CommonErrCode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.*;

class BindTicketUtilTest {

    private static final String SECRET = "a-test-bind-secret-at-least-32-bytes-long-xxxxx";

    private BindTicketUtil bindTicketUtil;

    @BeforeEach
    void setUp() {
        bindTicketUtil = new BindTicketUtil(SECRET, 10);
    }

    @Test
    void issue_then_parse_roundTrips() {
        String ticket = bindTicketUtil.issue("APPLE", "sub1", null);

        BindTicketPayload payload = bindTicketUtil.parse(ticket);

        assertThat(payload.provider()).isEqualTo("APPLE");
        assertThat(payload.externalId()).isEqualTo("sub1");
        assertThat(payload.rawOpenid()).isNull();
        assertThat(payload.jti()).isNotBlank();
    }

    @Test
    void parse_rejectsTokenSignedWithDifferentKey() {
        BindTicketUtil other = new BindTicketUtil("another-bind-secret-at-least-32-bytes-long-yyyy", 10);
        String foreignTicket = other.issue("APPLE", "sub1", null);

        assertThatThrownBy(() -> bindTicketUtil.parse(foreignTicket))
                .isInstanceOf(BusinessException.class)
                .extracting("errCode").isEqualTo(CommonErrCode.TOKEN_INVALID);
    }

    @Test
    void parse_rejectsAccessTypToken() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        String accessTyped = Jwts.builder()
                .claim("provider", "APPLE")
                .claim("externalId", "sub1")
                .claim(TokenType.CLAIM, TokenType.ACCESS)
                .id("jti-1")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 60000L))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> bindTicketUtil.parse(accessTyped))
                .isInstanceOf(BusinessException.class)
                .extracting("errCode").isEqualTo(CommonErrCode.TOKEN_INVALID);
    }

    @Test
    void parse_rejectsExpired() {
        BindTicketUtil shortLived = new BindTicketUtil(SECRET, 0);
        String ticket = shortLived.issue("APPLE", "sub1", null);

        assertThatThrownBy(() -> shortLived.parse(ticket))
                .isInstanceOf(BusinessException.class)
                .extracting("errCode").isEqualTo(CommonErrCode.TOKEN_INVALID);
    }

    @Test
    void issue_generatesDistinctJtiEachTime() {
        String ticket1 = bindTicketUtil.issue("APPLE", "sub1", null);
        String ticket2 = bindTicketUtil.issue("APPLE", "sub1", null);

        String jti1 = bindTicketUtil.parse(ticket1).jti();
        String jti2 = bindTicketUtil.parse(ticket2).jti();

        assertThat(jti1).isNotEqualTo(jti2);
    }
}
