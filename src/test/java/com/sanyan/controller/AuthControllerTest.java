package com.sanyan.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.dto.req.LoginReq;
import com.sanyan.dto.req.RegisterReq;
import com.sanyan.dto.req.SmsSendReq;
import com.sanyan.service.SmsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private SmsService smsService;
    @MockBean private StringRedisTemplate stringRedisTemplate;

    @Test
    void shouldSendSmsCode() throws Exception {
        SmsSendReq req = new SmsSendReq();
        req.setPhone("13800138000");

        mockMvc.perform(post("/api/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(smsService).sendCode("13800138000");
    }

    @Test
    void shouldRegisterNewUser() throws Exception {
        when(smsService.verifyCode("13800138001", "123456")).thenReturn(true);

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
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void shouldRejectRegisterWithWrongCode() throws Exception {
        when(smsService.verifyCode(anyString(), anyString())).thenReturn(false);

        RegisterReq req = new RegisterReq();
        req.setPhone("13800138002");
        req.setCode("000000");
        req.setPassword("mypassword");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errMsg").value("验证码错误"));
    }

    @Test
    void shouldLoginWithPassword() throws Exception {
        // 先注册
        when(smsService.verifyCode("13800138003", "123456")).thenReturn(true);
        RegisterReq regReq = new RegisterReq();
        regReq.setPhone("13800138003");
        regReq.setCode("123456");
        regReq.setPassword("mypassword");
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(regReq)));

        // 再登录
        LoginReq loginReq = new LoginReq();
        loginReq.setPhone("13800138003");
        loginReq.setPassword("mypassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.userId").isNotEmpty());
    }

    @Test
    void shouldRejectWrongPassword() throws Exception {
        // 先注册
        when(smsService.verifyCode("13800138004", "123456")).thenReturn(true);
        RegisterReq regReq = new RegisterReq();
        regReq.setPhone("13800138004");
        regReq.setCode("123456");
        regReq.setPassword("mypassword");
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(regReq)));

        // 错误密码登录
        LoginReq loginReq = new LoginReq();
        loginReq.setPhone("13800138004");
        loginReq.setPassword("wrongpassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }
}
