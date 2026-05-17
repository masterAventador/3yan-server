package com.sanyan.user.internal;

import com.sanyan.common.cache.KvCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SmsCodeSendServiceTest {

    @Mock private KvCache kvCache;

    private SmsCodeSendService smsCodeSendService;

    @BeforeEach
    void setUp() {
        smsCodeSendService = new SmsCodeSendService(kvCache);
    }

    @Test
    void shouldSendAndStoreCode() {
        smsCodeSendService.sendCode("13800138000");
        verify(kvCache).set(eq("sms:code:13800138000"), anyString(), eq(Duration.ofMinutes(5)));
    }

    @Test
    void shouldVerifyCorrectCode() {
        when(kvCache.get("sms:code:13800138000")).thenReturn("123456");
        assertThat(smsCodeSendService.verifyCode("13800138000", "123456")).isTrue();
        verify(kvCache).delete("sms:code:13800138000");
    }

    @Test
    void shouldRejectWrongCode() {
        when(kvCache.get("sms:code:13800138000")).thenReturn("123456");
        assertThat(smsCodeSendService.verifyCode("13800138000", "000000")).isFalse();
    }
}
