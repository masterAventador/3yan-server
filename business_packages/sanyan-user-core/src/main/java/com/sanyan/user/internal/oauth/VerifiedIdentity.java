package com.sanyan.user.internal.oauth;

/**
 * 第三方凭证校验结果。
 * <p>
 * externalId = 平台唯一标识（Apple sub / 微信 unionid）；
 * rawOpenid 仅微信用（Apple 恒为 null）。
 */
public record VerifiedIdentity(String externalId, String rawOpenid) {
}
