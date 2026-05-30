package com.sanyan.user.internal.oauth;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.URL;

/**
 * 提供 Apple 公钥 JWKSource Bean。
 * <p>
 * 用 {@link JWKSourceBuilder}（非 deprecated 的 RemoteJWKSet）构建，内置缓存 / 定时 refresh / 限流。
 * 抽成 Bean 是为了让 {@link AppleTokenVerifier} 构造注入，测试时可替换为本地公钥的 ImmutableJWKSet 离线验证。
 */
@Configuration
class AppleJwkConfig {

    /** Apple 公钥 JWKS endpoint。 */
    static final String APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys";

    @Bean
    JWKSource<SecurityContext> appleJwkSource() throws Exception {
        URL jwksUrl = URI.create(APPLE_JWKS_URL).toURL();
        return JWKSourceBuilder.<SecurityContext>create(jwksUrl).build();
    }
}
