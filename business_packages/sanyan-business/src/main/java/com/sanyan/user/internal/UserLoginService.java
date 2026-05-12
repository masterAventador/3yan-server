package com.sanyan.user.internal;

import com.sanyan.common.auth.JwtUtil;
import com.sanyan.common.error.BusinessException;
import com.sanyan.user.web.LoginData;
import com.sanyan.user.web.LoginReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserLoginService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public LoginData login(LoginReq req) {
        UserEntity user = userRepository.findByPhone(req.getPhone())
                .orElseThrow(() -> {
                    log.warn("登录失败，用户不存在: phone={}", req.getPhone());
                    return new BusinessException(UserErrCode.USER_NOT_FOUND);
                });
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            log.warn("登录失败，密码错误: phone={}", req.getPhone());
            throw new BusinessException(UserErrCode.WRONG_PASSWORD);
        }
        log.info("用户登录成功: userId={}, phone={}", user.getId(), user.getPhone());

        LoginData data = new LoginData();
        data.setUserId(user.getId());
        data.setToken(jwtUtil.generateToken(user.getId()));
        data.setNickname(user.getNickname());
        data.setAvatar(user.getAvatar());
        return data;
    }
}
