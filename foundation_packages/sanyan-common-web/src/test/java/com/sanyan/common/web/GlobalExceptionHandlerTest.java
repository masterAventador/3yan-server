package com.sanyan.common.web;

import com.sanyan.common.error.BusinessException;
import com.sanyan.common.error.CommonErrCode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void onBusinessException_returnsFailedRespWithErrCode() {
        BaseResp<Void> r = handler.onBusinessException(new BusinessException(CommonErrCode.TOKEN_INVALID));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getCode()).isEqualTo(401);
        assertThat(r.getMessage()).isEqualTo("登录态无效");
    }

    @Test
    void onUnknown_returnsInternalErrorResp() {
        BaseResp<Void> r = handler.onUnknown(new RuntimeException("oops"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getCode()).isEqualTo(500);
    }
}
