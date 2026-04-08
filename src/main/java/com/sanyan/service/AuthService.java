package com.sanyan.service;

import com.sanyan.dto.data.LoginData;
import com.sanyan.dto.req.LoginReq;
import com.sanyan.dto.req.PasswordResetReq;
import com.sanyan.dto.req.RegisterReq;
import com.sanyan.entity.User;
import com.sanyan.repository.UserRepository;
import com.sanyan.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final SmsService smsService;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public LoginData register(RegisterReq req) {
        if (!smsService.verifyCode(req.getPhone(), req.getCode())) {
            throw new IllegalArgumentException("验证码错误");
        }
        if (userRepository.existsByPhone(req.getPhone())) {
            throw new IllegalArgumentException("该手机号已注册");
        }

        User user = new User();
        user.setPhone(req.getPhone());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setNickname(req.getNickname() != null ? req.getNickname() : "用户" + req.getPhone().substring(7));
        userRepository.save(user);
        log.info("用户注册成功: phone={}, userId={}", user.getPhone(), user.getId());

        return buildLoginData(user);
    }

    public LoginData login(LoginReq req) {
        User user = userRepository.findByPhone(req.getPhone())
                .orElseThrow(() -> {
                    log.warn("登录失败，用户不存在: phone={}", req.getPhone());
                    return new IllegalArgumentException("用户不存在");
                });
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            log.warn("登录失败，密码错误: phone={}", req.getPhone());
            throw new IllegalArgumentException("密码错误");
        }
        log.info("用户登录成功: userId={}, phone={}", user.getId(), user.getPhone());
        return buildLoginData(user);
    }

    public void resetPassword(PasswordResetReq req) {
        if (!smsService.verifyCode(req.getPhone(), req.getCode())) {
            throw new IllegalArgumentException("验证码错误");
        }
        User user = userRepository.findByPhone(req.getPhone())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(user);
        log.info("密码重置成功: userId={}, phone={}", user.getId(), user.getPhone());
    }

    private LoginData buildLoginData(User user) {
        LoginData data = new LoginData();
        data.setUserId(user.getId());
        data.setToken(jwtUtil.generateToken(user.getId()));
        data.setNickname(user.getNickname());
        data.setAvatar(user.getAvatar());
        return data;
    }
}
