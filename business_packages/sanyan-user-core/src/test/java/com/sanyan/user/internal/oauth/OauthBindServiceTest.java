package com.sanyan.user.internal.oauth;

import com.sanyan.common.auth.BindTicketPayload;
import com.sanyan.common.auth.BindTicketUtil;
import com.sanyan.common.auth.JwtUtil;
import com.sanyan.common.cache.KvCache;
import com.sanyan.common.error.BusinessException;
import com.sanyan.common.error.CommonErrCode;
import com.sanyan.user.internal.SmsCodeSendService;
import com.sanyan.user.internal.UserAuthIdentityEntity;
import com.sanyan.user.internal.UserAuthIdentityRepository;
import com.sanyan.user.internal.UserEntity;
import com.sanyan.user.internal.UserErrCode;
import com.sanyan.user.internal.UserRepository;
import com.sanyan.user.web.OauthBindPhoneReq;
import com.sanyan.user.web.OauthLoginData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OauthBindServiceTest {

    @Mock private BindTicketUtil bindTicketUtil;
    @Mock private KvCache kvCache;
    @Mock private SmsCodeSendService smsCodeSendService;
    @Mock private UserRepository userRepo;
    @Mock private UserAuthIdentityRepository identityRepo;
    @Mock private JwtUtil jwtUtil;
    @Mock private OauthIdentityBinder identityBinder;

    @InjectMocks private OauthBindService service;

    private static final String JTI = "jti-123";
    private static final String USED_KEY = "oauth:bindticket:used:" + JTI;

    private static OauthBindPhoneReq req(String password) {
        OauthBindPhoneReq r = new OauthBindPhoneReq();
        r.setBindTicket("ticket");
        r.setPhone("13800138000");
        r.setCode("123456");
        r.setPassword(password);
        return r;
    }

    private static BindTicketPayload applePayload() {
        return new BindTicketPayload("APPLE", "sub-1", null, JTI);
    }

    private static UserEntity userWithPassword(long id, String encodedPwd) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setPhone("13800138000");
        u.setPassword(encodedPwd);
        u.setNickname("阿明");
        return u;
    }

    private static UserEntity userNoPassword(long id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setPhone("13800138000");
        u.setPassword(null);
        u.setNickname("阿明");
        return u;
    }

    // ① ④ ⑦ ⑧(新建) ⑨a ⑩
    @Test
    void bind_newPhone_createsUserAndIdentity_returnsJwt() {
        UserEntity created = userNoPassword(42L);
        when(bindTicketUtil.parse("ticket")).thenReturn(applePayload());
        when(kvCache.setIfAbsent(eq(USED_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(smsCodeSendService.verifyCode("13800138000", "123456")).thenReturn(true);
        when(userRepo.findByPhone("13800138000")).thenReturn(Optional.empty());
        when(identityBinder.createUserWithIdentity("13800138000", Provider.APPLE, "sub-1", null))
                .thenReturn(created);
        when(jwtUtil.generateToken(42L)).thenReturn("jwt-new");

        OauthLoginData data = service.bindPhone(req(null));

        assertFalse(data.isNeedBind());
        assertFalse(data.isNeedMergeAuth());
        assertEquals(42L, data.getUserId());
        assertEquals("jwt-new", data.getToken());
        verify(identityBinder).createUserWithIdentity("13800138000", Provider.APPLE, "sub-1", null);
    }

    // ⑧(已有密码账号 + 正确密码 → merge) ⑨b
    @Test
    void bind_existingPhoneWithPassword_correctPassword_merges() {
        UserEntity existing = userWithPassword(7L, "encoded");
        when(bindTicketUtil.parse("ticket")).thenReturn(applePayload());
        when(kvCache.setIfAbsent(eq(USED_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(smsCodeSendService.verifyCode("13800138000", "123456")).thenReturn(true);
        when(userRepo.findByPhone("13800138000")).thenReturn(Optional.of(existing));
        when(identityBinder.matchesPassword("right", "encoded")).thenReturn(true);
        when(jwtUtil.generateToken(7L)).thenReturn("jwt-merge");

        OauthLoginData data = service.bindPhone(req("right"));

        assertFalse(data.isNeedMergeAuth());
        assertEquals(7L, data.getUserId());
        assertEquals("jwt-merge", data.getToken());
        verify(identityBinder).attachIdentityToUser(7L, Provider.APPLE, "sub-1", null);
        verify(identityBinder, never()).createUserWithIdentity(anyString(), any(), anyString(), any());
    }

    // ⑧(已有密码账号 + 传了错密码 → 真身份校验失败，抛 NEED_MERGE_AUTH 错误码，不写库)
    @Test
    void bind_existingPhoneWrongPassword_throwsNeedMergeAuth() {
        UserEntity existing = userWithPassword(7L, "encoded");
        when(bindTicketUtil.parse("ticket")).thenReturn(applePayload());
        when(kvCache.setIfAbsent(eq(USED_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(smsCodeSendService.verifyCode("13800138000", "123456")).thenReturn(true);
        when(userRepo.findByPhone("13800138000")).thenReturn(Optional.of(existing));
        when(identityBinder.matchesPassword("wrong", "encoded")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.bindPhone(req("wrong")));

        assertEquals(UserErrCode.NEED_MERGE_AUTH, ex.getErrCode());
        verify(identityBinder, never()).attachIdentityToUser(any(Long.class), any(), anyString(), any());
        verify(identityBinder, never()).createUserWithIdentity(anyString(), any(), anyString(), any());
    }

    // ⑧(已有密码账号 + 没传密码 → needMerge 数据态，success=true，不抛错不写库)
    @Test
    void bind_existingPhoneWithPassword_noPasswordProvided_returnsNeedMergeData() {
        UserEntity existing = userWithPassword(7L, "encoded");
        when(bindTicketUtil.parse("ticket")).thenReturn(applePayload());
        when(kvCache.setIfAbsent(eq(USED_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(smsCodeSendService.verifyCode("13800138000", "123456")).thenReturn(true);
        when(userRepo.findByPhone("13800138000")).thenReturn(Optional.of(existing));

        OauthLoginData data = service.bindPhone(req(null));

        assertTrue(data.isNeedMergeAuth());
        assertFalse(data.isNeedBind());
        assertNull(data.getToken());
        verify(identityBinder, never()).matchesPassword(anyString(), anyString());
        verify(identityBinder, never()).attachIdentityToUser(any(Long.class), any(), anyString(), any());
        verify(identityBinder, never()).createUserWithIdentity(anyString(), any(), anyString(), any());
    }

    // ⑧(已有账号但无密码 → needMerge 数据态，本期无法验本人，success=true，不抛错不写库)
    @Test
    void bind_existingPhoneNoPassword_returnsNeedMergeData() {
        UserEntity existing = userNoPassword(7L);
        when(bindTicketUtil.parse("ticket")).thenReturn(applePayload());
        when(kvCache.setIfAbsent(eq(USED_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(smsCodeSendService.verifyCode("13800138000", "123456")).thenReturn(true);
        when(userRepo.findByPhone("13800138000")).thenReturn(Optional.of(existing));

        OauthLoginData data = service.bindPhone(req("anything"));

        assertTrue(data.isNeedMergeAuth());
        assertFalse(data.isNeedBind());
        assertNull(data.getToken());
        verify(identityBinder, never()).matchesPassword(anyString(), anyString());
        verify(identityBinder, never()).attachIdentityToUser(any(Long.class), any(), anyString(), any());
        verify(identityBinder, never()).createUserWithIdentity(anyString(), any(), anyString(), any());
    }

    // ④ jti 一次性消费锁：已用 → BIND_TICKET_USED，且不验短信/不查库/不写库
    @Test
    void bind_rejectsUsedJti() {
        when(bindTicketUtil.parse("ticket")).thenReturn(applePayload());
        when(kvCache.setIfAbsent(eq(USED_KEY), anyString(), any(Duration.class))).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.bindPhone(req(null)));

        assertEquals(UserErrCode.BIND_TICKET_USED, ex.getErrCode());
        verifyNoInteractions(smsCodeSendService);
        verifyNoInteractions(userRepo);
        verifyNoInteractions(identityBinder);
    }

    // ① parse 抛（验签/typ/exp 失败）→ BIND_TICKET_INVALID
    @Test
    void bind_rejectsInvalidTicket() {
        when(bindTicketUtil.parse("ticket"))
                .thenThrow(new BusinessException(CommonErrCode.TOKEN_INVALID, "bindTicket 无效或已过期"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.bindPhone(req(null)));

        assertEquals(UserErrCode.BIND_TICKET_INVALID, ex.getErrCode());
        verifyNoInteractions(kvCache);
        verifyNoInteractions(smsCodeSendService);
        verifyNoInteractions(userRepo);
        verifyNoInteractions(identityBinder);
    }

    // ⑤ provider 非法 → BIND_TICKET_INVALID（jti 锁后、短信前）
    @Test
    void bind_rejectsBadProvider() {
        when(bindTicketUtil.parse("ticket"))
                .thenReturn(new BindTicketPayload("GITHUB", "sub-1", null, JTI));
        when(kvCache.setIfAbsent(eq(USED_KEY), anyString(), any(Duration.class))).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.bindPhone(req(null)));

        assertEquals(UserErrCode.BIND_TICKET_INVALID, ex.getErrCode());
        verifyNoInteractions(smsCodeSendService);
    }

    // ⑦ 短信码错 → SMS_CODE_INVALID（不建号/不查库）
    @Test
    void bind_rejectsBadSmsCode() {
        when(bindTicketUtil.parse("ticket")).thenReturn(applePayload());
        when(kvCache.setIfAbsent(eq(USED_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(smsCodeSendService.verifyCode("13800138000", "123456")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.bindPhone(req(null)));

        assertEquals(UserErrCode.SMS_CODE_INVALID, ex.getErrCode());
        verifyNoInteractions(userRepo);
        verifyNoInteractions(identityBinder);
    }

    // S7 insert-or-recover：并发写撞唯一约束 → 重查命中 → 降级登录发 JWT（不抛 500）
    @Test
    void bind_concurrentInsertConflict_recoversAsLogin() {
        when(bindTicketUtil.parse("ticket")).thenReturn(applePayload());
        when(kvCache.setIfAbsent(eq(USED_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(smsCodeSendService.verifyCode("13800138000", "123456")).thenReturn(true);
        when(userRepo.findByPhone("13800138000")).thenReturn(Optional.empty());
        when(identityBinder.createUserWithIdentity("13800138000", Provider.APPLE, "sub-1", null))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_provider_external"));
        UserAuthIdentityEntity recovered = new UserAuthIdentityEntity();
        recovered.setUserId(99L);
        when(identityRepo.findByProviderAndExternalId(Provider.APPLE, "sub-1"))
                .thenReturn(Optional.of(recovered));
        when(userRepo.findById(99L)).thenReturn(Optional.of(userNoPassword(99L)));
        when(jwtUtil.generateToken(99L)).thenReturn("jwt-recover");

        OauthLoginData data = service.bindPhone(req(null));

        assertFalse(data.isNeedMergeAuth());
        assertEquals(99L, data.getUserId());
        assertEquals("jwt-recover", data.getToken());
    }

    // S7 真异常：写库撞唯一约束但重查查不到 → rethrow（不静默吞）
    @Test
    void bind_concurrentInsertConflict_recoverMiss_rethrows() {
        when(bindTicketUtil.parse("ticket")).thenReturn(applePayload());
        when(kvCache.setIfAbsent(eq(USED_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(smsCodeSendService.verifyCode("13800138000", "123456")).thenReturn(true);
        when(userRepo.findByPhone("13800138000")).thenReturn(Optional.empty());
        when(identityBinder.createUserWithIdentity("13800138000", Provider.APPLE, "sub-1", null))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("boom"));
        when(identityRepo.findByProviderAndExternalId(Provider.APPLE, "sub-1"))
                .thenReturn(Optional.empty());

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> service.bindPhone(req(null)));
    }

    // 顺序断言：④ jti 消费锁严格在 ⑦ 验短信之前
    @Test
    void bind_jtiLockBeforeSmsVerify() {
        UserEntity created = userNoPassword(42L);
        when(bindTicketUtil.parse("ticket")).thenReturn(applePayload());
        when(kvCache.setIfAbsent(eq(USED_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(smsCodeSendService.verifyCode("13800138000", "123456")).thenReturn(true);
        when(userRepo.findByPhone("13800138000")).thenReturn(Optional.empty());
        when(identityBinder.createUserWithIdentity("13800138000", Provider.APPLE, "sub-1", null))
                .thenReturn(created);
        when(jwtUtil.generateToken(42L)).thenReturn("jwt-new");

        service.bindPhone(req(null));

        var ordered = inOrder(kvCache, smsCodeSendService);
        ordered.verify(kvCache).setIfAbsent(eq(USED_KEY), anyString(), any(Duration.class));
        ordered.verify(smsCodeSendService).verifyCode("13800138000", "123456");
    }
}
