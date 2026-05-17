package com.sanyan.chat.internal;

import com.sanyan.common.error.ErrCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ChatErrCode implements ErrCode {
    MESSAGE_PROCESSING_FAILED(2001, "消息处理失败");

    private final int code;
    private final String defaultMessage;
}
