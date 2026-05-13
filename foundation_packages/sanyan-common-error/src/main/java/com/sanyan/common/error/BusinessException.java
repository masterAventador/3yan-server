package com.sanyan.common.error;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final ErrCode errCode;

    public BusinessException(ErrCode errCode) {
        super(errCode.getDefaultMessage());
        this.errCode = errCode;
    }

    public BusinessException(ErrCode errCode, String overrideMessage) {
        super(overrideMessage);
        this.errCode = errCode;
    }
}
