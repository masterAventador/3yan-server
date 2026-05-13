package com.sanyan.user.internal;

import com.sanyan.common.cache.KvCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsCodeSendService {

    private final KvCache kvCache;
    private static final String CODE_PREFIX = "sms:code:";
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Random RANDOM = new Random();

    public void sendCode(String phone) {
        String code = String.format("%06d", RANDOM.nextInt(1000000));
        kvCache.set(CODE_PREFIX + phone, code, CODE_TTL);
        // TODO: 对接阿里云短信 SDK，当前仅打印到控制台
        log.info("短信验证码 [{}]: {}", phone, code);
    }

    public boolean verifyCode(String phone, String code) {
        String stored = kvCache.get(CODE_PREFIX + phone);
        if (stored != null && stored.equals(code)) {
            kvCache.delete(CODE_PREFIX + phone);
            return true;
        }
        return false;
    }
}
