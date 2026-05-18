package com.sanyan.user.internal;

import com.sanyan.common.cache.KvCache;
import com.sanyan.common.error.BusinessException;
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
    private final SmsProvider smsProvider;

    private static final String CODE_PREFIX = "sms:code:";
    private static final String THROTTLE_PREFIX = "sms:throttle:";
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration THROTTLE_TTL = Duration.ofMinutes(1);
    private static final Random RANDOM = new Random();

    public void sendCode(String phone) {
        if (!kvCache.setIfAbsent(THROTTLE_PREFIX + phone, "1", THROTTLE_TTL)) {
            throw new BusinessException(UserErrCode.SMS_SEND_TOO_FREQUENT);
        }
        String code = String.format("%06d", RANDOM.nextInt(1000000));
        kvCache.set(CODE_PREFIX + phone, code, CODE_TTL);
        smsProvider.send(phone, code);
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
