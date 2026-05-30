package com.sanyan.user.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.common.error.BusinessException;
import com.sanyan.common.test.TestApplication;
import com.sanyan.common.web.GlobalExceptionHandler;
import com.sanyan.user.internal.SmsCodeSendService;
import com.sanyan.user.internal.UserEntity;
import com.sanyan.user.internal.UserErrCode;
import com.sanyan.user.internal.UserLoginService;
import com.sanyan.user.internal.UserRegisterService;
import com.sanyan.user.internal.oauth.OauthBindService;
import com.sanyan.user.internal.oauth.OauthChallengeService;
import com.sanyan.user.internal.oauth.OauthLoginService;
import com.sanyan.user.internal.oauth.Provider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@ContextConfiguration(classes = TestApplication.class)
@Import({AuthController.class, GlobalExceptionHandler.class})
class AuthControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private UserRegisterService userRegisterService;
    @MockBean private UserLoginService userLoginService;
    @MockBean private SmsCodeSendService smsCodeSendService;
    @MockBean private OauthChallengeService oauthChallengeService;
    @MockBean private OauthLoginService oauthLoginService;
    @MockBean private OauthBindService oauthBindService;

    @Test
    void shouldSendSmsCode() throws Exception {
        SmsSendReq req = new SmsSendReq();
        req.setPhone("13800138000");

        mockMvc.perform(post("/api/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(smsCodeSendService).sendCode("13800138000");
    }

    @Test
    void shouldRegisterNewUser() throws Exception {
        LoginData loginData = new LoginData();
        loginData.setUserId(1001L);
        loginData.setToken("fake-token");
        loginData.setNickname("小明");
        when(userRegisterService.register(any(RegisterReq.class))).thenReturn(loginData);

        RegisterReq req = new RegisterReq();
        req.setPhone("13800138001");
        req.setCode("123456");
        req.setPassword("mypassword");
        req.setNickname("小明");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").value("fake-token"));
    }

    @Test
    void shouldRejectRegisterWithWrongCode() throws Exception {
        when(userRegisterService.register(any(RegisterReq.class)))
                .thenThrow(new BusinessException(UserErrCode.SMS_CODE_INVALID));

        RegisterReq req = new RegisterReq();
        req.setPhone("13800138002");
        req.setCode("000000");
        req.setPassword("mypassword");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("验证码错误"));
    }

    @Test
    void shouldLoginWithPassword() throws Exception {
        LoginData loginData = new LoginData();
        loginData.setUserId(2002L);
        loginData.setToken("login-token");
        when(userLoginService.login(any(LoginReq.class))).thenReturn(loginData);

        LoginReq loginReq = new LoginReq();
        loginReq.setPhone("13800138003");
        loginReq.setPassword("mypassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").value("login-token"))
                .andExpect(jsonPath("$.data.userId").value(2002));
    }

    @Test
    void shouldRejectWrongPassword() throws Exception {
        when(userLoginService.login(any(LoginReq.class)))
                .thenThrow(new BusinessException(UserErrCode.WRONG_PASSWORD));

        LoginReq loginReq = new LoginReq();
        loginReq.setPhone("13800138004");
        loginReq.setPassword("wrongpassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("密码错误"));
    }

    @Test
    void shouldIssueOauthChallengeNonce() throws Exception {
        when(oauthChallengeService.issueNonce()).thenReturn("nonce-abc-123");

        mockMvc.perform(post("/api/auth/oauth/challenge")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.nonce").value("nonce-abc-123"));

        verify(oauthChallengeService).issueNonce();
    }

    @Test
    void shouldOauthLoginWhenIdentityBound() throws Exception {
        UserEntity user = new UserEntity();
        user.setId(3003L);
        user.setNickname("阿婉");
        user.setAvatar("a.png");
        when(oauthLoginService.login(any(OauthLoginReq.class)))
                .thenReturn(OauthLoginData.loggedIn(user, "oauth-token"));

        OauthLoginReq req = new OauthLoginReq();
        req.setProvider(Provider.APPLE);
        req.setCredential("identity-token");
        req.setNonce("nonce-1");

        mockMvc.perform(post("/api/auth/oauth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.needBind").value(false))
                .andExpect(jsonPath("$.data.userId").value(3003))
                .andExpect(jsonPath("$.data.token").value("oauth-token"));
    }

    @Test
    void shouldReturnBindTicketWhenIdentityNotBound() throws Exception {
        when(oauthLoginService.login(any(OauthLoginReq.class)))
                .thenReturn(OauthLoginData.needBind("bind-ticket-xyz"));

        OauthLoginReq req = new OauthLoginReq();
        req.setProvider(Provider.WECHAT);
        req.setCredential("wx-code");

        mockMvc.perform(post("/api/auth/oauth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.needBind").value(true))
                .andExpect(jsonPath("$.data.bindTicket").value("bind-ticket-xyz"));
    }

    @Test
    void shouldBindPhoneAndReturnToken() throws Exception {
        UserEntity user = new UserEntity();
        user.setId(4004L);
        user.setNickname("小婉");
        when(oauthBindService.bindPhone(any(OauthBindPhoneReq.class)))
                .thenReturn(OauthLoginData.loggedIn(user, "bind-token"));

        OauthBindPhoneReq req = new OauthBindPhoneReq();
        req.setBindTicket("ticket");
        req.setPhone("13800138000");
        req.setCode("123456");

        mockMvc.perform(post("/api/auth/oauth/bind-phone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.needMergeAuth").value(false))
                .andExpect(jsonPath("$.data.userId").value(4004))
                .andExpect(jsonPath("$.data.token").value("bind-token"));
    }

    @Test
    void shouldReturnNeedMergeAuthWhenPhoneAlreadyRegistered() throws Exception {
        when(oauthBindService.bindPhone(any(OauthBindPhoneReq.class)))
                .thenReturn(OauthLoginData.needMergeAuth());

        OauthBindPhoneReq req = new OauthBindPhoneReq();
        req.setBindTicket("ticket");
        req.setPhone("13800138000");
        req.setCode("123456");

        mockMvc.perform(post("/api/auth/oauth/bind-phone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.needMergeAuth").value(true))
                .andExpect(jsonPath("$.data.token").doesNotExist());
    }

    @Test
    void shouldRejectBindWithWrongMergePassword() throws Exception {
        when(oauthBindService.bindPhone(any(OauthBindPhoneReq.class)))
                .thenThrow(new BusinessException(UserErrCode.NEED_MERGE_AUTH));

        OauthBindPhoneReq req = new OauthBindPhoneReq();
        req.setBindTicket("ticket");
        req.setPhone("13800138000");
        req.setCode("123456");
        req.setPassword("wrong-password");

        mockMvc.perform(post("/api/auth/oauth/bind-phone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(1014))
                .andExpect(jsonPath("$.message").value("该手机号已注册，请验证账号本人或登录后绑定"));
    }

    @Test
    void shouldRejectBindWithInvalidTicket() throws Exception {
        when(oauthBindService.bindPhone(any(OauthBindPhoneReq.class)))
                .thenThrow(new BusinessException(UserErrCode.BIND_TICKET_INVALID));

        OauthBindPhoneReq req = new OauthBindPhoneReq();
        req.setBindTicket("bad");
        req.setPhone("13800138000");
        req.setCode("123456");

        mockMvc.perform(post("/api/auth/oauth/bind-phone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(1012))
                .andExpect(jsonPath("$.message").value("绑定会话无效或已过期"));
    }
}
