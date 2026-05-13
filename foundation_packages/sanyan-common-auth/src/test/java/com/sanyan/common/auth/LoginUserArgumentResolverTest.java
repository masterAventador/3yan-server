package com.sanyan.common.auth;

import com.sanyan.common.error.CommonErrCode;
import com.sanyan.common.web.BaseResp;
import com.sanyan.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LoginUserArgumentResolverTest {

    private static final String SECRET = "test-secret-key-at-least-256-bits-long-for-hmac-sha";

    private MockMvc mvc;
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 30);
        LoginUserArgumentResolver resolver = new LoginUserArgumentResolver(jwtUtil);
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setCustomArgumentResolvers(resolver)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldResolveUserIdFromValidBearerToken() throws Exception {
        String token = jwtUtil.generateToken(99L);
        mvc.perform(get("/test/whoami").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(99));
    }

    @Test
    void shouldAcceptTokenWithoutBearerPrefix() throws Exception {
        String token = jwtUtil.generateToken(7L);
        mvc.perform(get("/test/whoami").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(7));
    }

    @Test
    void shouldReturnFailureWhenAuthorizationHeaderMissing() throws Exception {
        mvc.perform(get("/test/whoami"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(CommonErrCode.TOKEN_INVALID.getCode()));
    }

    @Test
    void shouldReturnFailureWhenTokenInvalid() throws Exception {
        mvc.perform(get("/test/whoami").header("Authorization", "Bearer not.a.real.token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(CommonErrCode.TOKEN_INVALID.getCode()));
    }

    @RestController
    static class TestController {
        @GetMapping("/test/whoami")
        public BaseResp<Long> whoami(@LoginUser Long userId) {
            return BaseResp.success(userId);
        }
    }
}
