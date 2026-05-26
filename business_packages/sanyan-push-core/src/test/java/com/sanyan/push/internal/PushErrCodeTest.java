package com.sanyan.push.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PushErrCodeTest {

    @Test
    void codes_should_match_appendix_values() {
        assertThat(PushErrCode.DEVICE_TOKEN_INVALID.getCode()).isEqualTo(8001);
        assertThat(PushErrCode.PUSH_SEND_FAILED.getCode()).isEqualTo(8002);
    }

    @Test
    void all_codes_should_be_within_push_range() {
        for (PushErrCode c : PushErrCode.values()) {
            assertThat(c.getCode()).isBetween(8000, 8999);
        }
    }

    @Test
    void messages_should_not_be_blank() {
        for (PushErrCode c : PushErrCode.values()) {
            assertThat(c.getDefaultMessage()).isNotBlank();
        }
    }
}
