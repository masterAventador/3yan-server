package com.sanyan.user.internal.sms;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class StubSmsSenderTest {
    @Test
    void send_doesNotThrow() {
        SmsSender sender = new StubSmsSender();
        assertThatCode(() -> sender.send("13800138000", "123456")).doesNotThrowAnyException();
    }
}
