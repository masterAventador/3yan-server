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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OauthLoginServiceTest {

    @Mock private AppleTokenVerifier appleVerifier;
    @Mock private WechatCodeVerifier wechatVerifier;
    @Mock private UserAuthIdentityRepository identityRepo;
    @Mock private UserRepository userRepo;
    @Mock private JwtUtil jwtUtil;
    @Mock private BindTicketUtil bindTicketUtil;
    @Mock private KvCache kvCache;

    @InjectMocks private OauthLoginService service;

    private static OauthLoginReq appleReq(String nonce) {
        OauthLoginReq req = new OauthLoginReq();
        req.setProvider(Provider.APPLE);
        req.setCredential("identity-token");
        req.setNonce(nonce);
        return req;
    }

    private static OauthLoginReq wechatReq() {
        OauthLoginReq req = new OauthLoginReq();
        req.setProvider(Provider.WECHAT);
        req.setCredential("wx-code");
        return req;
    }

    private static UserEntity userWithId(long id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setNickname("小明");
        u.setAvatar("avatar.png");
        return u;
    }

    private static UserAuthIdentityEntity identity(long userId) {
        UserAuthIdentityEntity e = new UserAuthIdentityEntity();
        e.setUserId(userId);
        return e;
    }

    @Test
    void login_apple_existingIdentity_returnsLoginData() {
        when(kvCache.getAndDelete(OauthChallengeService.NONCE_KEY_PREFIX + "nonce-1")).thenReturn("1");
        when(appleVerifier.verify("identity-token", "nonce-1"))
                .thenReturn(new VerifiedIdentity("sub1", null));
        when(identityRepo.findByProviderAndExternalId(Provider.APPLE, "sub1"))
                .thenReturn(Optional.of(identity(5L)));
        when(userRepo.findById(5L)).thenReturn(Optional.of(userWithId(5L)));
        when(jwtUtil.generateToken(5L)).thenReturn("jwt");

        OauthLoginData data = service.login(appleReq("nonce-1"));

        assertFalse(data.isNeedBind());
        assertEquals(5L, data.getUserId());
        assertEquals("jwt", data.getToken());
        assertEquals("小明", data.getNickname());
        assertNull(data.getBindTicket());
        verify(bindTicketUtil, never()).issue(anyString(), anyString(), any());
    }

    @Test
    void login_apple_newIdentity_returnsBindTicket() {
        when(kvCache.getAndDelete(OauthChallengeService.NONCE_KEY_PREFIX + "nonce-1")).thenReturn("1");
        when(appleVerifier.verify("identity-token", "nonce-1"))
                .thenReturn(new VerifiedIdentity("sub1", null));
        when(identityRepo.findByProviderAndExternalId(Provider.APPLE, "sub1"))
                .thenReturn(Optional.empty());
        when(bindTicketUtil.issue("APPLE", "sub1", null)).thenReturn("tkt");

        OauthLoginData data = service.login(appleReq("nonce-1"));

        assertTrue(data.isNeedBind());
        assertEquals("tkt", data.getBindTicket());
        assertNull(data.getUserId());
        assertNull(data.getToken());
        verify(jwtUtil, never()).generateToken(any());
    }

    @Test
    void login_apple_rejectsConsumedOrMissingNonce() {
        when(kvCache.getAndDelete(OauthChallengeService.NONCE_KEY_PREFIX + "nonce-1")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.login(appleReq("nonce-1")));

        assertEquals(UserErrCode.OAUTH_VERIFY_FAILED, ex.getErrCode());
        verifyNoInteractions(appleVerifier);
    }

    @Test
    void login_wechat_existingIdentity() {
        when(wechatVerifier.verify("wx-code", null))
                .thenReturn(new VerifiedIdentity("union1", "openid1"));
        when(identityRepo.findByProviderAndExternalId(Provider.WECHAT, "union1"))
                .thenReturn(Optional.of(identity(7L)));
        when(userRepo.findById(7L)).thenReturn(Optional.of(userWithId(7L)));
        when(jwtUtil.generateToken(7L)).thenReturn("wx-jwt");

        OauthLoginData data = service.login(wechatReq());

        assertFalse(data.isNeedBind());
        assertEquals(7L, data.getUserId());
        assertEquals("wx-jwt", data.getToken());
        // 微信不消费 nonce
        verifyNoInteractions(kvCache);
    }

    @Test
    void login_wechat_newIdentity_returnsBindTicket() {
        when(wechatVerifier.verify("wx-code", null))
                .thenReturn(new VerifiedIdentity("union1", "openid1"));
        when(identityRepo.findByProviderAndExternalId(Provider.WECHAT, "union1"))
                .thenReturn(Optional.empty());
        when(bindTicketUtil.issue("WECHAT", "union1", "openid1")).thenReturn("wx-tkt");

        OauthLoginData data = service.login(wechatReq());

        assertTrue(data.isNeedBind());
        assertEquals("wx-tkt", data.getBindTicket());
        verifyNoInteractions(kvCache);
        verify(jwtUtil, never()).generateToken(any());
    }
}
