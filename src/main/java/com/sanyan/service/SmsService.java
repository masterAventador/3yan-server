package com.sanyan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsService {

    private final StringRedisTemplate redisTemplate;
    private static final String CODE_PREFIX = "sms:code:";
    private static final Random RANDOM = new Random();

    public void sendCode(String phone) {
        String code = String.format("%06d", RANDOM.nextInt(1000000));
        redisTemplate.opsForValue().set(CODE_PREFIX + phone, code, 5, TimeUnit.MINUTES);
        // TODO: 对接阿里云短信 SDK，当前仅打印到控制台
        log.info("短信验证码 [{}]: {}", phone, code);
    }

    public boolean verifyCode(String phone, String code) {
        String stored = redisTemplate.opsForValue().get(CODE_PREFIX + phone);
        if (stored != null && stored.equals(code)) {
            redisTemplate.delete(CODE_PREFIX + phone);
            return true;
        }
        return false;
    }
}
