package com.sanyan.common.web;

import com.sanyan.common.error.BusinessException;
import com.sanyan.common.error.CommonErrCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public BaseResp<Void> onBusinessException(BusinessException e) {
        return BaseResp.failed(e.getErrCode().getCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public BaseResp<Void> onValidation(Exception e) {
        String msg = "参数错误";
        if (e instanceof MethodArgumentNotValidException ex) {
            var err = ex.getBindingResult().getAllErrors().get(0);
            msg = err.getDefaultMessage();
        }
        return BaseResp.failed(CommonErrCode.PARAM_INVALID.getCode(), msg);
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public BaseResp<Void> onDataIntegrity(org.springframework.dao.DataIntegrityViolationException e) {
        log.warn("数据完整性冲突", e);
        return BaseResp.failed(CommonErrCode.CONFLICT.getCode(), CommonErrCode.CONFLICT.getDefaultMessage());
    }

    @ExceptionHandler(Exception.class)
    public BaseResp<Void> onUnknown(Exception e) {
        log.error("unhandled exception", e);
        return BaseResp.failed(CommonErrCode.INTERNAL_ERROR.getCode(), "服务器错误，请稍后重试");
    }
}
