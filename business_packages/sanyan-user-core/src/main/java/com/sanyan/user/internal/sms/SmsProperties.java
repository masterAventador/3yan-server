package com.sanyan.user.internal.sms;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 短信配置。provider=stub(dev 默认)|tencent(prod)。
 * <p>
 * 注册方式：由 SmsConfig（@Configuration + @EnableConfigurationProperties）激活，
 * 本类不加 @Component（对齐 ApnsProperties / ProactiveProperties 风格）。
 *
 * <pre>
 * sanyan:
 *   sms:
 *     provider: stub          # stub | tencent
 *     tencent:
 *       secret-id: XXX
 *       secret-key: XXX
 *       sdk-app-id: XXX
 *       sign-name: XXX
 *       template-id: XXX
 *       region: ap-guangzhou
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "sanyan.sms")
public class SmsProperties {
    /** stub | tencent */
    private String provider = "stub";
    private Tencent tencent = new Tencent();

    @Data
    public static class Tencent {
        private String secretId;
        private String secretKey;
        private String sdkAppId;
        private String signName;
        private String templateId;
        /** 地域，默认广州 */
        private String region = "ap-guangzhou";
    }
}
