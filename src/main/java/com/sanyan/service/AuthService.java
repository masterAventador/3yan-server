package com.sanyan.service;

import com.sanyan.dto.data.LoginData;
import com.sanyan.dto.req.LoginReq;
import com.sanyan.dto.req.PasswordResetReq;
import com.sanyan.dto.req.RegisterReq;
import com.sanyan.entity.User;
import com.sanyan.repository.UserRepository;
import com.sanyan.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

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

        return buildLoginData(user);
    }

    public LoginData login(LoginReq req) {
        User user = userRepository.findByPhone(req.getPhone())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("密码错误");
        }
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
