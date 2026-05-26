package com.sanyan.push.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.common.auth.JwtUtil;
import com.sanyan.common.auth.LoginUserArgumentResolver;
import com.sanyan.common.auth.WebMvcConfig;
import com.sanyan.common.error.CommonErrCode;
import com.sanyan.common.test.TestApplication;
import com.sanyan.common.web.GlobalExceptionHandler;
import com.sanyan.push.PushApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DeviceTokenController.class)
@ContextConfiguration(classes = TestApplication.class)
@Import({DeviceTokenController.class, WebMvcConfig.class, LoginUserArgumentResolver.class, GlobalExceptionHandler.class})
class DeviceTokenControllerIT {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockBean
    private PushApi pushApi;

    @MockBean
    private JwtUtil jwtUtil;

    @Test
    void register_should_call_pushApi_and_return_success() throws Exception {
        when(jwtUtil.parseUserId("test-token")).thenReturn(42L);
        String body = objectMapper.writeValueAsString(
                Map.of("platform", "ios", "vendor", "apns", "token", "device-tok-123"));

        mockMvc.perform(post("/api/devices/register")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(pushApi).registerDevice(42L, "ios", "apns", "device-tok-123");
    }

    @Test
    void register_should_reject_blank_token() throws Exception {
        when(jwtUtil.parseUserId("test-token")).thenReturn(42L);
        String body = objectMapper.writeValueAsString(
                Map.of("platform", "ios", "vendor", "apns", "token", ""));

        mockMvc.perform(post("/api/devices/register")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(CommonErrCode.PARAM_INVALID.getCode()));
    }
}
