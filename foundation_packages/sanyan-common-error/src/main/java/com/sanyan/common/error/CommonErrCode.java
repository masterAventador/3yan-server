package com.sanyan.common.error;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CommonErrCode implements ErrCode {
    TOKEN_EXPIRED(400, "登录态过期"),
    TOKEN_INVALID(401, "登录态无效"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "数据冲突，请重试"),
    PARAM_INVALID(410, "参数错误"),
    INTERNAL_ERROR(500, "服务器错误，请稍后重试");

    private final int code;
    private final String defaultMessage;
}
