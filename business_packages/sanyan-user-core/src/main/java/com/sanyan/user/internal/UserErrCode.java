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
    SMS_SEND_FAILED(1007, "短信发送失败，请稍后重试"),
    SMS_VERIFY_TOO_MANY(1008, "验证失败次数过多，请重新获取验证码"),
    LOGIN_PASSWORD_NOT_SET(1009, "该账号未设置密码，请用第三方登录"),
    OAUTH_VERIFY_FAILED(1010, "第三方登录校验失败"),
    WECHAT_UNIONID_MISSING(1011, "微信账号缺少 unionid，无法登录"),
    BIND_TICKET_INVALID(1012, "绑定会话无效或已过期"),
    BIND_TICKET_USED(1013, "绑定会话已使用"),
    NEED_MERGE_AUTH(1014, "该手机号已注册，请验证账号本人或登录后绑定");

    private final int code;
    private final String defaultMessage;
}
