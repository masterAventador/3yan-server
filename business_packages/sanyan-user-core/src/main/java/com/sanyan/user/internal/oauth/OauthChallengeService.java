package com.sanyan.user.internal.oauth;

import com.sanyan.common.cache.KvCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 下发第三方登录用的一次性 nonce（S8 防重放）。
 * <p>
 * 客户端登录前先调 /oauth/challenge 拿 nonce，Apple 登录时把 nonce 透传给 Apple，
 * Apple 把 SHA256(nonce) 写进 identityToken 的 n claim；后端登录时校验 token 的 n 命中本次下发的 nonce，
 * 并原子消费（getAndDelete）确保 nonce 一次性，防止 identityToken 重放。
 */
@Service
@RequiredArgsConstructor
public class OauthChallengeService {

    /** nonce 在 KvCache 中的 key 前缀。登录消费（Task 10）用同一前缀拼 key。 */
    public static final String NONCE_KEY_PREFIX = "oauth:nonce:";

    /** nonce 有效期：客户端拿到后须在 5 分钟内完成第三方登录。 */
    private static final Duration NONCE_TTL = Duration.ofMinutes(5);

    private final KvCache kvCache;

    public String issueNonce() {
        String nonce = UUID.randomUUID().toString();
        kvCache.set(NONCE_KEY_PREFIX + nonce, "1", NONCE_TTL);
        return nonce;
    }
}
