package com.sanyan.user.web;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * /oauth/challenge 响应：一次性 nonce（S8 防重放，Apple 登录透传给 Apple）。
 */
@Data
@AllArgsConstructor
public class OauthChallengeData {
    private String nonce;
}
