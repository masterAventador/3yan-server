package com.sanyan.user.internal;

import com.sanyan.common.auth.JwtUtil;
import com.sanyan.common.error.BusinessException;
import com.sanyan.user.web.LoginData;
import com.sanyan.user.web.RegisterReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserRegisterService {

    private final UserRepository userRepository;
    private final SmsCodeSendService smsCodeSendService;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Transactional
    public LoginData register(RegisterReq req) {
        if (!smsCodeSendService.verifyCode(req.getPhone(), req.getCode())) {
            throw new BusinessException(UserErrCode.SMS_CODE_INVALID);
        }
        if (userRepository.existsByPhone(req.getPhone())) {
            throw new BusinessException(UserErrCode.PHONE_ALREADY_REGISTERED);
        }

        UserEntity user = new UserEntity();
        user.setPhone(req.getPhone());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setNickname(req.getNickname() != null ? req.getNickname() : "用户" + req.getPhone().substring(7));
        userRepository.save(user);
        log.info("用户注册成功: phone={}, userId={}", user.getPhone(), user.getId());

        LoginData data = new LoginData();
        data.setUserId(user.getId());
        data.setToken(jwtUtil.generateToken(user.getId()));
        data.setNickname(user.getNickname());
        data.setAvatar(user.getAvatar());
        return data;
    }
}
