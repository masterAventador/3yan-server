package com.sanyan.user.internal.oauth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 第三方登录配置。
 * <p>
 * 注册方式：由 {@link OauthConfig}（@Configuration + @EnableConfigurationProperties）激活，
 * 本类不加 @Component（对齐 SmsProperties / ProactiveProperties 风格）。
 * <p>
 * 含 apple（S1 identityToken 校验）+ wechat（S2 code 换 openid/unionid）；bind-ticket 由后续 Task 补全。
 *
 * <pre>
 * sanyan:
 *   oauth:
 *     apple:
 *       allowed-aud:            # Apple identityToken aud 白名单（客户端 bundleId）
 *         - com.sanyan.app
 *     wechat:
 *       appid:                  # 微信开放平台 App ID
 *       secret:                 # 微信 AppSecret（服务端 code 换 token 用，禁止下发客户端 / 入日志）
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "sanyan.oauth")
public class OauthProperties {

    private Apple apple = new Apple();

    private Wechat wechat = new Wechat();

    @Data
    public static class Apple {
        /** identityToken 的 aud 白名单（允许的客户端 bundleId）。 */
        private List<String> allowedAud = new ArrayList<>();
    }

    @Data
    public static class Wechat {
        /** 微信开放平台 App ID。 */
        private String appid;
        /** 微信 AppSecret，仅服务端用 code 换 access_token/openid/unionid，禁止下发客户端或写入日志。 */
        private String secret;
    }
}
