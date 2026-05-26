package com.sanyan.push.internal;

import com.sanyan.common.error.ErrCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Push 模块错误码 enum（Plan 4 引入）。
 *
 * <p>code 区间 <b>8000-8999</b>，登记在 {@code sanyan-common-error/ERROR_CODE_REGISTRY.md}。
 * 启动时由 {@code ErrCodeConflictDetector} 扫描；同 code 重复定义会让上下文启动失败。
 */
@Getter
@AllArgsConstructor
public enum PushErrCode implements ErrCode {

    DEVICE_TOKEN_INVALID(8001, "设备 token 无效"),
    PUSH_SEND_FAILED(8002, "推送发送失败"),
    ;

    private final int code;
    private final String defaultMessage;
}
