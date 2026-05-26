package com.sanyan.proactive.internal;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ProactiveErrCodeTest {

    @Test
    void all_codes_should_be_within_7000_7999_range() {
        for (ProactiveErrCode c : ProactiveErrCode.values()) {
            assertThat(c.getCode()).isBetween(7000, 7999);
            assertThat(c.getDefaultMessage()).isNotBlank();
        }
    }

    @Test
    void codes_should_be_unique() {
        long distinct = Arrays.stream(ProactiveErrCode.values()).map(ProactiveErrCode::getCode).distinct().count();
        assertThat(distinct).isEqualTo(ProactiveErrCode.values().length);
    }

    @Test
    void should_expose_expected_codes() {
        assertThat(ProactiveErrCode.PROACTIVE_GENERATE_FAILED.getCode()).isEqualTo(7001);
        assertThat(ProactiveErrCode.PROACTIVE_EVENT_TYPE_INVALID.getCode()).isEqualTo(7002);
        assertThat(ProactiveErrCode.PROACTIVE_EVENT_NOT_FOUND.getCode()).isEqualTo(7003);
    }
}
