package com.sanyan.user.internal.oauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.sanyan.common.error.BusinessException;
import com.sanyan.user.internal.UserErrCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * Apple identityToken（RS256 JWT）校验器（安全要求 S1）。
 * <p>
 * 用 Nimbus 从 Apple JWKS 验签 + 验全部 claim，取 {@code sub} 作为 Apple 唯一标识。
 * 固定校验顺序（任一失败抛 {@link UserErrCode#OAUTH_VERIFY_FAILED}）：
 * <ol>
 *   <li>解析 JWT</li>
 *   <li>验签：{@link JWSVerificationKeySelector} 只接受 RS256（自动拒 none / HS*）</li>
 *   <li>iss == https://appleid.apple.com</li>
 *   <li>aud ∈ allowedAud 白名单</li>
 *   <li>exp 未过期（允许 ±60s 时钟偏移）</li>
 *   <li>nonce：SHA256(expectedNonce) == token 的 nonce claim</li>
 * </ol>
 * iss / aud / exp 由 {@link DefaultJWTClaimsVerifier} 在 {@code process()} 内一次性校验；nonce 单独手动比对。
 */
@Slf4j
@Component
public class AppleTokenVerifier implements ThirdPartyAuthVerifier {

    private static final String APPLE_ISSUER = "https://appleid.apple.com";
    private static final String NONCE_CLAIM = "nonce";
    /** 允许的时钟偏移（秒），用于 exp / nbf 校验。 */
    private static final int CLOCK_SKEW_SECONDS = 60;

    private final JWKSource<SecurityContext> jwkSource;
    private final OauthProperties properties;

    public AppleTokenVerifier(JWKSource<SecurityContext> jwkSource, OauthProperties properties) {
        this.jwkSource = jwkSource;
        this.properties = properties;
    }

    @Override
    public VerifiedIdentity verify(String identityToken, String expectedNonce) {
        try {
            SignedJWT signedJwt = SignedJWT.parse(identityToken);

            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            // 只接受 RS256：alg 白名单，自动拒 none / HS*
            processor.setJWSKeySelector(
                    new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
            // iss / aud / exp 一次性校验（exp 由 DefaultJWTClaimsVerifier 内置校验，含 ±60s 时钟偏移）
            processor.setJWTClaimsSetVerifier(buildClaimsVerifier());

            JWTClaimsSet claims = processor.process(signedJwt, null);

            // nonce：Apple 把 raw nonce 的 SHA256（hex）写进 token 的 nonce claim
            String expectedNonceHash = sha256Hex(expectedNonce);
            if (!expectedNonceHash.equals(claims.getStringClaim(NONCE_CLAIM))) {
                throw new BusinessException(UserErrCode.OAUTH_VERIFY_FAILED);
            }

            return new VerifiedIdentity(claims.getSubject(), null);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 只记异常 cause 供生产排查；不打 identityToken 内容（含 PII）
            log.warn("Apple identityToken 校验失败", e);
            throw new BusinessException(UserErrCode.OAUTH_VERIFY_FAILED);
        }
    }

    /** 构造 iss + aud + exp 校验器：要求 iss 固定为 Apple、aud 命中白名单、exp 存在且未过期。 */
    private DefaultJWTClaimsVerifier<SecurityContext> buildClaimsVerifier() {
        // 用 null 容忍的 HashSet：Nimbus 构造期内部会对 acceptedAudience 调 contains(null)，
        // 不可传 Set.of(...)（immutable set 对 null 入参抛 NPE）。
        Set<String> acceptedAud = new HashSet<>(properties.getApple().getAllowedAud());
        DefaultJWTClaimsVerifier<SecurityContext> verifier = new DefaultJWTClaimsVerifier<>(
                acceptedAud,
                new JWTClaimsSet.Builder().issuer(APPLE_ISSUER).build(),
                Set.of("sub", "exp"),
                Set.of());
        verifier.setMaxClockSkew(CLOCK_SKEW_SECONDS);
        return verifier;
    }

    /** raw nonce 的 SHA-256 摘要（小写 hex）。Apple 写进 token 时用此格式。 */
    private static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(UserErrCode.OAUTH_VERIFY_FAILED);
        }
    }
}
