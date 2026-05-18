package com.sanyan.user.internal;

import com.sanyan.common.cache.KvCache;
import com.sanyan.common.error.BusinessException;
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
    @Mock private SmsProvider smsProvider;

    private SmsCodeSendService smsCodeSendService;

    @BeforeEach
    void setUp() {
        smsCodeSendService = new SmsCodeSendService(kvCache, smsProvider);
    }

    @Test
    void shouldSendAndStoreCode() {
        when(kvCache.setIfAbsent(eq("sms:throttle:13800138000"), anyString(), eq(Duration.ofMinutes(1))))
                .thenReturn(true);

        smsCodeSendService.sendCode("13800138000");

        verify(kvCache).set(eq("sms:code:13800138000"), anyString(), eq(Duration.ofMinutes(5)));
        verify(smsProvider).send(eq("13800138000"), anyString());
    }

    @Test
    void shouldRejectWhenThrottled() {
        when(kvCache.setIfAbsent(eq("sms:throttle:13800138000"), anyString(), eq(Duration.ofMinutes(1))))
                .thenReturn(false);

        assertThatThrownBy(() -> smsCodeSendService.sendCode("13800138000"))
                .isInstanceOf(BusinessException.class)
                .extracting("errCode").isEqualTo(UserErrCode.SMS_SEND_TOO_FREQUENT);

        verify(kvCache, never()).set(startsWith("sms:code:"), anyString(), any());
        verify(smsProvider, never()).send(any(), any());
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
