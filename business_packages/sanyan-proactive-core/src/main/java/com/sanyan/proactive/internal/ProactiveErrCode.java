package com.sanyan.proactive.internal;

import com.sanyan.common.error.ErrCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 主动消息域错误码（7000-7999，见 ERROR_CODE_REGISTRY）。 */
@Getter
@AllArgsConstructor
public enum ProactiveErrCode implements ErrCode {
    PROACTIVE_GENERATE_FAILED(7001, "主动消息生成失败"),
    PROACTIVE_EVENT_TYPE_INVALID(7002, "主动事件类型不合法");

    private final int code;
    private final String defaultMessage;
}
