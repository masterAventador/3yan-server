package com.sanyan.push.internal.apns;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * APNs 接入配置（spec §8）：基于 token-based auth（.p8 AuthKey）。
 * 本期占位——字段读取就绪，实推待证书下发后接入 pushy {@code ApnsClient}。
 *
 * <pre>
 * sanyan:
 *   push:
 *     apns:
 *       p8: classpath:AuthKey_XXXX.p8   # AuthKey 文件路径
 *       keyId: XXXXXXXXXX               # Key ID
 *       teamId: YYYYYYYYYY              # Apple Team ID
 *       topic: com.sanyan.app           # bundle id
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "sanyan.push.apns")
public class ApnsProperties {
    private String p8;
    private String keyId;
    private String teamId;
    private String topic;
}
