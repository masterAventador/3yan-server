package com.sanyan.user.internal;

import com.sanyan.common.cache.KvCache;
import com.sanyan.common.error.BusinessException;
import com.sanyan.user.internal.sms.SmsSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsCodeSendService {

    private final KvCache kvCache;
    private final SmsSender smsSender;

    public static final String CODE_PREFIX = "sms:code:";
    public static final String RATE_PREFIX = "sms:rate:";
    private static final String DAILY_PREFIX = "sms:daily:";
    private static final String FAIL_PREFIX = "sms:fail:";
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration RATE_TTL = Duration.ofSeconds(60);
    private static final Duration DAILY_TTL = Duration.ofHours(24);
    private static final Duration FAIL_TTL = Duration.ofMinutes(10);
    private static final int DAILY_CAP = 10;
    private static final int MAX_FAIL = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    public void sendCode(String phone) {
        if (!kvCache.setIfAbsent(RATE_PREFIX + phone, "1", RATE_TTL)) {
            throw new BusinessException(UserErrCode.SMS_SEND_TOO_FREQUENT);
        }
        long today = kvCache.increment(DAILY_PREFIX + phone, DAILY_TTL);
        if (today > DAILY_CAP) {
            throw new BusinessException(UserErrCode.SMS_SEND_TOO_FREQUENT);
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        kvCache.set(CODE_PREFIX + phone, code, CODE_TTL);
        smsSender.send(phone, code);
    }

    public boolean verifyCode(String phone, String code) {
        long fails = kvCache.increment(FAIL_PREFIX + phone, FAIL_TTL);
        if (fails > MAX_FAIL) {
            throw new BusinessException(UserErrCode.SMS_VERIFY_TOO_MANY);
        }
        String stored = kvCache.getAndDelete(CODE_PREFIX + phone);
        if (stored != null && stored.equals(code)) {
            kvCache.delete(FAIL_PREFIX + phone);
            return true;
        }
        return false;
    }
}
