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
 * 本 Task（Apple）只含 apple 部分；wechat / bind-ticket 由后续 Task 补全。
 *
 * <pre>
 * sanyan:
 *   oauth:
 *     apple:
 *       allowed-aud:            # Apple identityToken aud 白名单（客户端 bundleId）
 *         - com.sanyan.app
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "sanyan.oauth")
public class OauthProperties {

    private Apple apple = new Apple();

    @Data
    public static class Apple {
        /** identityToken 的 aud 白名单（允许的客户端 bundleId）。 */
        private List<String> allowedAud = new ArrayList<>();
    }
}
