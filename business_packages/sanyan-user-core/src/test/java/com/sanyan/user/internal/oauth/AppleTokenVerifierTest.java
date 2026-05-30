package com.sanyan.user.internal.oauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sanyan.common.error.BusinessException;
import com.sanyan.user.internal.UserErrCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AppleTokenVerifier 单测（S1 安全核心，逐分支验）。
 * <p>
 * 策略：测试内自生成一对 RSA 密钥，用私钥签 Apple 风格 JWT，公钥包成 ImmutableJWKSet 注入 verifier，离线验证。
 */
class AppleTokenVerifierTest {

    private static final String APPLE_ISSUER = "https://appleid.apple.com";
    private static final String VALID_AUD = "com.sanyan.app";
    private static final String RAW_NONCE = "test-nonce";
    private static final String APPLE_SUB = "001234.abcdef0123456789.0001";

    private RSAKey rsaKey;
    private AppleTokenVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("apple-test-kid").generate();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(
                new com.nimbusds.jose.jwk.JWKSet(rsaKey.toPublicJWK()));
        OauthProperties props = new OauthProperties();
        props.getApple().setAllowedAud(List.of(VALID_AUD));
        verifier = new AppleTokenVerifier(jwkSource, props);
    }

    /** 测试用：复刻 Apple 把 raw nonce 的 SHA256（hex）写进 token 的逻辑。 */
    private static String sha256Hex(String raw) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    /** 用给定密钥 + claims 签一个 RS256 JWT。 */
    private String signRs256(RSAKey signingKey, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    /** 标准合法 claims（可在各用例上覆写单个字段）。 */
    private JWTClaimsSet.Builder validClaims() throws Exception {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(APPLE_ISSUER)
                .audience(VALID_AUD)
                .subject(APPLE_SUB)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("nonce", sha256Hex(RAW_NONCE));
    }

    @Test
    void verify_ok() throws Exception {
        String token = signRs256(rsaKey, validClaims().build());

        VerifiedIdentity result = verifier.verify(token, RAW_NONCE);

        assertEquals(APPLE_SUB, result.externalId());
        assertNull(result.rawOpenid());
    }

    @Test
    void verify_rejectsWrongIssuer() throws Exception {
        String token = signRs256(rsaKey,
                validClaims().issuer("https://evil.example.com").build());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifier.verify(token, RAW_NONCE));
        assertEquals(UserErrCode.OAUTH_VERIFY_FAILED, ex.getErrCode());
    }

    @Test
    void verify_rejectsAudNotInWhitelist() throws Exception {
        String token = signRs256(rsaKey,
                validClaims().audience("com.other.app").build());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifier.verify(token, RAW_NONCE));
        assertEquals(UserErrCode.OAUTH_VERIFY_FAILED, ex.getErrCode());
    }

    @Test
    void verify_rejectsExpired() throws Exception {
        Instant past = Instant.now().minusSeconds(3600);
        String token = signRs256(rsaKey,
                validClaims().expirationTime(Date.from(past)).build());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifier.verify(token, RAW_NONCE));
        assertEquals(UserErrCode.OAUTH_VERIFY_FAILED, ex.getErrCode());
    }

    @Test
    void verify_rejectsBadSignature() throws Exception {
        // 用另一对密钥签，验签公钥对不上
        RSAKey otherKey = new RSAKeyGenerator(2048).keyID("apple-test-kid").generate();
        String token = signRs256(otherKey, validClaims().build());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifier.verify(token, RAW_NONCE));
        assertEquals(UserErrCode.OAUTH_VERIFY_FAILED, ex.getErrCode());
    }

    @Test
    void verify_rejectsWrongNonce() throws Exception {
        // token 内 nonce claim 是合法值，但 verify 传入的 expectedNonce 对不上
        String token = signRs256(rsaKey, validClaims().build());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifier.verify(token, "different-nonce"));
        assertEquals(UserErrCode.OAUTH_VERIFY_FAILED, ex.getErrCode());
    }

    @Test
    void verify_rejectsNonRs256() throws Exception {
        // 用 HS256 签：JWSVerificationKeySelector 只认 RS256，必须拒
        byte[] secret = new byte[32];
        for (int i = 0; i < secret.length; i++) {
            secret[i] = (byte) i;
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
                validClaims().build());
        jwt.sign(new MACSigner(secret));
        String token = jwt.serialize();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifier.verify(token, RAW_NONCE));
        assertEquals(UserErrCode.OAUTH_VERIFY_FAILED, ex.getErrCode());
    }
}
