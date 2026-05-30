package com.sanyan.common.web;

import com.sanyan.common.error.BusinessException;
import com.sanyan.common.error.CommonErrCode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void onDataIntegrity_returnsConflictCode() {
        BaseResp<Void> r = handler.onDataIntegrity(
            new org.springframework.dao.DataIntegrityViolationException("dup key"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getCode()).isEqualTo(409); // CONFLICT, not 500
    }

    @Test
    void onValidation_methodArgumentNotValid_returnsFieldErrorMessage() {
        // arrange: 构造 MethodArgumentNotValidException，使其 binding result 含一个 FieldError
        org.springframework.validation.BindingResult br = mock(org.springframework.validation.BindingResult.class);
        org.springframework.validation.FieldError fieldError =
            new org.springframework.validation.FieldError("user", "phone", "手机号格式错误");
        when(br.getAllErrors()).thenReturn(java.util.List.of(fieldError));

        org.springframework.web.bind.MethodArgumentNotValidException ex =
            mock(org.springframework.web.bind.MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(br);

        BaseResp<Void> r = handler.onValidation(ex);

        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getCode()).isEqualTo(410); // PARAM_INVALID
        assertThat(r.getMessage()).isEqualTo("手机号格式错误");
    }

    @Test
    void onValidation_bindException_returnsDefaultParamErrorMessage() {
        org.springframework.validation.BindException ex =
            mock(org.springframework.validation.BindException.class);

        BaseResp<Void> r = handler.onValidation(ex);

        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getCode()).isEqualTo(410); // PARAM_INVALID
        assertThat(r.getMessage()).isEqualTo("参数错误");
    }
}
