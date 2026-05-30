package com.sanyan.user.internal.oauth;

import com.sanyan.common.auth.BindTicketUtil;
import com.sanyan.common.auth.JwtUtil;
import com.sanyan.common.cache.KvCache;
import com.sanyan.common.error.BusinessException;
import com.sanyan.user.internal.UserAuthIdentityEntity;
import com.sanyan.user.internal.UserAuthIdentityRepository;
import com.sanyan.user.internal.UserEntity;
import com.sanyan.user.internal.UserErrCode;
import com.sanyan.user.internal.UserRepository;
import com.sanyan.user.web.OauthLoginData;
import com.sanyan.user.web.OauthLoginReq;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 第三方登录核心编排（Task 10）：校验第三方凭证 → 查身份 → 命中则登录、未绑则发 bindTicket。
 * <p>
 * Apple 登录前会原子消费 /oauth/challenge 下发的一次性 nonce（{@link KvCache#getAndDelete}）防重放；
 * 微信不走 nonce（其 code 一次性由 {@link WechatCodeVerifier} 内部保证）。
 * verifier 按 {@link Provider} 选择：APPLE → {@link AppleTokenVerifier}，WECHAT → {@link WechatCodeVerifier}。
 * <p>
 * 本服务命中分支只读、未命中分支也不写库（仅签发 bindTicket，建号延后到 /oauth/bind-phone），故无需事务。
 */
@Service
@RequiredArgsConstructor
public class OauthLoginService {

    private final AppleTokenVerifier appleVerifier;
    private final WechatCodeVerifier wechatVerifier;
    private final UserAuthIdentityRepository identityRepo;
    private final UserRepository userRepo;
    private final JwtUtil jwtUtil;
    private final BindTicketUtil bindTicketUtil;
    private final KvCache kvCache;

    public OauthLoginData login(OauthLoginReq req) {
        Provider provider = req.getProvider();

        // Apple：原子消费一次性 nonce 防重放（未下发 / 已用过 → 拒，且不触达 verifier）
        if (provider == Provider.APPLE) {
            String nonceKey = OauthChallengeService.NONCE_KEY_PREFIX + req.getNonce();
            if (req.getNonce() == null || kvCache.getAndDelete(nonceKey) == null) {
                throw new BusinessException(UserErrCode.OAUTH_VERIFY_FAILED);
            }
        }

        ThirdPartyAuthVerifier verifier = (provider == Provider.APPLE) ? appleVerifier : wechatVerifier;
        VerifiedIdentity identity = verifier.verify(req.getCredential(), req.getNonce());

        Optional<UserAuthIdentityEntity> bound =
                identityRepo.findByProviderAndExternalId(provider, identity.externalId());
        if (bound.isPresent()) {
            UserEntity user = userRepo.findById(bound.get().getUserId())
                    .orElseThrow(() -> new BusinessException(UserErrCode.USER_NOT_FOUND));
            return OauthLoginData.loggedIn(user, jwtUtil.generateToken(user.getId()));
        }

        String bindTicket = bindTicketUtil.issue(provider.name(), identity.externalId(), identity.rawOpenid());
        return OauthLoginData.needBind(bindTicket);
    }
}
