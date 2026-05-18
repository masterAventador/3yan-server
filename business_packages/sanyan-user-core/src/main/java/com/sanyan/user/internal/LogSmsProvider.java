package com.sanyan.user.internal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LogSmsProvider implements SmsProvider {

    @Override
    public void send(String phone, String code) {
        log.info("短信验证码 [{}]: {}", phone, code);
    }
}
