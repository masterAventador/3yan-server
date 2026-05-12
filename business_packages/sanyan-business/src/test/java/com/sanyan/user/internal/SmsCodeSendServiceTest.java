package com.sanyan.user.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SmsCodeSendServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private SmsCodeSendService smsCodeSendService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        smsCodeSendService = new SmsCodeSendService(redisTemplate);
    }

    @Test
    void shouldSendAndStoreCode() {
        smsCodeSendService.sendCode("13800138000");
        verify(valueOps).set(eq("sms:code:13800138000"), anyString(), eq(5L), eq(TimeUnit.MINUTES));
    }

    @Test
    void shouldVerifyCorrectCode() {
        when(valueOps.get("sms:code:13800138000")).thenReturn("123456");
        assertThat(smsCodeSendService.verifyCode("13800138000", "123456")).isTrue();
        verify(redisTemplate).delete("sms:code:13800138000");
    }

    @Test
    void shouldRejectWrongCode() {
        when(valueOps.get("sms:code:13800138000")).thenReturn("123456");
        assertThat(smsCodeSendService.verifyCode("13800138000", "000000")).isFalse();
    }
}
