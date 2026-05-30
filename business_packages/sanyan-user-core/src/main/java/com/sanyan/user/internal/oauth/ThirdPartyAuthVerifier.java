package com.sanyan.user.internal.oauth;

/**
 * 第三方凭证校验器。Apple / 微信各实现一个。
 */
public interface ThirdPartyAuthVerifier {

    /**
     * 校验第三方凭证，返回已验证身份；任一校验失败抛 {@code BusinessException(OAUTH_VERIFY_FAILED)}。
     *
     * @param credential    第三方凭证（Apple identityToken / 微信 code）
     * @param expectedNonce 期望的 nonce（仅 Apple 用；微信传 null）。
     *                      Apple 调用方必须传非空 expectedNonce（传 null 会校验失败）。
     */
    VerifiedIdentity verify(String credential, String expectedNonce);
}
