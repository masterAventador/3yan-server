package com.sanyan.common.auth;

/**
 * bindTicket 解析后的已校验第三方身份载荷。
 * provider 用 String（不依赖 business 层的 Provider enum，foundation 不依赖 business），
 * 在 user-core 侧使用时再转成 Provider。
 */
public record BindTicketPayload(String provider, String externalId, String rawOpenid, String jti) {
}
