package com.sanyan.user.internal;

import com.sanyan.common.auth.JwtUtil;
import com.sanyan.common.error.BusinessException;
import com.sanyan.user.web.LoginReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserLoginServiceTest {

    private UserRepository userRepository;
    private JwtUtil jwtUtil;
    private BCryptPasswordEncoder passwordEncoder;
    private UserLoginService service;

    @BeforeEach
    void setup() {
        userRepository = mock(UserRepository.class);
        jwtUtil = mock(JwtUtil.class);
        passwordEncoder = mock(BCryptPasswordEncoder.class);
        service = new UserLoginService(userRepository, jwtUtil, passwordEncoder);
    }

    private LoginReq loginReq(String phone, String password) {
        LoginReq req = new LoginReq();
        req.setPhone(phone);
        req.setPassword(password);
        return req;
    }

    @Test
    void login_throwsWhenAccountHasNoPassword() {
        UserEntity noPwdUser = UserTestFixtures.userWithPhone("13800138000");
        noPwdUser.setPassword(null);
        when(userRepository.findByPhone("13800138000")).thenReturn(Optional.of(noPwdUser));

        assertThatThrownBy(() -> service.login(loginReq("13800138000", "anyPassword")))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrCode())
            .isEqualTo(UserErrCode.LOGIN_PASSWORD_NOT_SET);

        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void login_throwsWhenUserNotFound() {
        when(userRepository.findByPhone("13800138000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(loginReq("13800138000", "pwd")))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrCode())
            .isEqualTo(UserErrCode.USER_NOT_FOUND);
    }

    @Test
    void login_throwsWhenPasswordWrong() {
        UserEntity user = UserTestFixtures.userWithPhone("13800138000");
        when(userRepository.findByPhone("13800138000")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", user.getPassword())).thenReturn(false);

        assertThatThrownBy(() -> service.login(loginReq("13800138000", "wrong")))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrCode())
            .isEqualTo(UserErrCode.WRONG_PASSWORD);
    }

    @Test
    void login_succeedsWithCorrectPassword() {
        UserEntity user = UserTestFixtures.userWithPhone("13800138000");
        user.setId(42L);
        when(userRepository.findByPhone("13800138000")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct", user.getPassword())).thenReturn(true);
        when(jwtUtil.generateToken(42L)).thenReturn("token-xyz");

        var data = service.login(loginReq("13800138000", "correct"));

        assertThat(data.getUserId()).isEqualTo(42L);
        assertThat(data.getToken()).isEqualTo("token-xyz");
    }
}
