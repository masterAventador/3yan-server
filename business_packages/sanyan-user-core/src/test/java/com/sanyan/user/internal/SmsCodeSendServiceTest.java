package com.sanyan.user.internal;

import com.sanyan.common.cache.KvCache;
import com.sanyan.common.error.BusinessException;
import com.sanyan.user.internal.sms.SmsSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmsCodeSendServiceTest {

    private KvCache kvCache;
    private SmsSender smsSender;
    private SmsCodeSendService service;

    @BeforeEach
    void setup() {
        kvCache = mock(KvCache.class);
        smsSender = mock(SmsSender.class);
        service = new SmsCodeSendService(kvCache, smsSender);
    }

    @Test
    void sendCode_storesAndSendsSixDigit() {
        when(kvCache.setIfAbsent(startsWith("sms:rate:"), any(), any())).thenReturn(true);
        when(kvCache.increment(startsWith("sms:daily:"), any())).thenReturn(1L);

        service.sendCode("13800138000");

        ArgumentCaptor<String> codeCap = ArgumentCaptor.forClass(String.class);
        verify(smsSender).send(eq("13800138000"), codeCap.capture());
        assertThat(codeCap.getValue()).matches("\\d{6}");
        verify(kvCache).set(eq("sms:code:13800138000"), eq(codeCap.getValue()), any());
    }

    @Test
    void sendCode_throwsWhenRateLimited() {
        when(kvCache.setIfAbsent(startsWith("sms:rate:"), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.sendCode("13800138000")).isInstanceOf(BusinessException.class);
        verify(smsSender, never()).send(any(), any());
    }

    @Test
    void sendCode_throwsWhenDailyCapExceeded() {
        when(kvCache.setIfAbsent(startsWith("sms:rate:"), any(), any())).thenReturn(true);
        when(kvCache.increment(startsWith("sms:daily:"), any())).thenReturn(11L);
        assertThatThrownBy(() -> service.sendCode("13800138000")).isInstanceOf(BusinessException.class);
        verify(smsSender, never()).send(any(), any());
    }

    @Test
    void verifyCode_atomicSuccess() {
        when(kvCache.increment(startsWith("sms:fail:"), any())).thenReturn(1L);
        when(kvCache.getAndDelete("sms:code:13800138000")).thenReturn("123456");
        assertThat(service.verifyCode("13800138000", "123456")).isTrue();
        verify(kvCache).getAndDelete("sms:code:13800138000");
    }

    @Test
    void verifyCode_successClearsFailCounter() {
        when(kvCache.increment(startsWith("sms:fail:"), any())).thenReturn(1L);
        when(kvCache.getAndDelete("sms:code:13800138000")).thenReturn("123456");
        assertThat(service.verifyCode("13800138000", "123456")).isTrue();
        verify(kvCache).delete(startsWith("sms:fail:"));
    }

    @Test
    void verifyCode_wrongCode_returnsFalse() {
        when(kvCache.increment(startsWith("sms:fail:"), any())).thenReturn(1L);
        when(kvCache.getAndDelete("sms:code:13800138000")).thenReturn("999999");
        assertThat(service.verifyCode("13800138000", "123456")).isFalse();
    }

    @Test
    void verifyCode_lockedAfterTooManyFailures() {
        when(kvCache.increment(startsWith("sms:fail:"), any())).thenReturn(6L);
        assertThatThrownBy(() -> service.verifyCode("13800138000", "123456"))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrCode())
            .isEqualTo(UserErrCode.SMS_VERIFY_TOO_MANY);
        verify(kvCache, never()).getAndDelete(any());
    }
}
