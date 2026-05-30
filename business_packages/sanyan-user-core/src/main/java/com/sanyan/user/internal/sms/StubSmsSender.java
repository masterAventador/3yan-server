package com.sanyan.user.internal.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** dev/test 用：只打日志不真发。由 sanyan.sms.provider=stub（默认）激活。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sanyan.sms.provider", havingValue = "stub", matchIfMissing = true)
public class StubSmsSender implements SmsSender {
    @Override
    public void send(String phone, String code) {
        log.info("[stub-sms] 验证码 [{}]: {}（未真发，dev/test 模式）", phone, code);
    }
}
