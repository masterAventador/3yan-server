package com.sanyan.user.web;

import com.sanyan.user.internal.oauth.Provider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 第三方登录请求。
 * <p>
 * Apple 登录：credential = identityToken，nonce 必传（防重放，须先调 /oauth/challenge 拿）；
 * 微信登录：credential = code，nonce 可空。
 */
@Data
public class OauthLoginReq {

    @NotNull
    private Provider provider;

    @NotBlank
    private String credential;

    /** Apple 必传（防重放）；微信可空。 */
    private String nonce;
}
