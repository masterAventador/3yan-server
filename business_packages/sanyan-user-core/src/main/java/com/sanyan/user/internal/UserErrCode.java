package com.sanyan.user.internal;

import com.sanyan.common.error.ErrCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UserErrCode implements ErrCode {
    PHONE_ALREADY_REGISTERED(1001, "手机号已注册"),
    USER_NOT_FOUND(1002, "用户不存在"),
    WRONG_PASSWORD(1003, "密码错误"),
    SMS_CODE_INVALID(1004, "验证码错误"),
    SMS_CODE_EXPIRED(1005, "验证码已过期"),
    SMS_SEND_TOO_FREQUENT(1006, "请稍后再试"),
    SMS_PROVIDER_FAILED(1007, "短信发送失败，请稍后重试");

    private final int code;
    private final String defaultMessage;
}
