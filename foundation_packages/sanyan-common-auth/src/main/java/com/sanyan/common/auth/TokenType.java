package com.sanyan.common.auth;

/** JWT 用途隔离常量（登录 token vs bindTicket），单点定义。 */
public final class TokenType {
    private TokenType() {}
    public static final String CLAIM = "typ";
    public static final String ACCESS = "ACCESS";
    public static final String BIND = "BIND";
}
