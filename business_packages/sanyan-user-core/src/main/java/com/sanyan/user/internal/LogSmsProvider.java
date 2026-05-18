package com.sanyan.user.internal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认 / 联调用的 SmsProvider：只把验证码打到 server 日志，不真发短信。
 * 朋友联调或本地 dev 时看 server log 找验证码。
 *
 * <p>{@code sanyan.sms.provider} 未配置或配为 {@code log} 时激活（matchIfMissing=true）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sanyan.sms.provider", havingValue = "log", matchIfMissing = true)
public class LogSmsProvider implements SmsProvider {

    @Override
    public void send(String phone, String code) {
        log.info("短信验证码 [{}]: {}", phone, code);
    }
}
