package com.sanyan.common.web;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BaseRespTest {
    @Test
    void success_returnsResponseWithDataAndZeroCode() {
        BaseResp<String> r = BaseResp.success("hello");
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getCode()).isEqualTo(0);
        assertThat(r.getMessage()).isEqualTo("ok");
        assertThat(r.getData()).isEqualTo("hello");
    }

    @Test
    void failed_returnsResponseWithCodeAndMessageAndNullData() {
        BaseResp<Void> r = BaseResp.failed(1003, "密码错误");
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getCode()).isEqualTo(1003);
        assertThat(r.getMessage()).isEqualTo("密码错误");
        assertThat(r.getData()).isNull();
    }
}
