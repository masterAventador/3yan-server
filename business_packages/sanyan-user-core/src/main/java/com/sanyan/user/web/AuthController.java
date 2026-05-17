package com.sanyan.user.web;

import com.sanyan.common.web.BaseResp;
import com.sanyan.user.internal.SmsCodeSendService;
import com.sanyan.user.internal.UserLoginService;
import com.sanyan.user.internal.UserRegisterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRegisterService userRegisterService;
    private final UserLoginService userLoginService;
    private final SmsCodeSendService smsCodeSendService;

    @PostMapping("/sms/send")
    public BaseResp<Void> sendSms(@Valid @RequestBody SmsSendReq req) {
        smsCodeSendService.sendCode(req.getPhone());
        return BaseResp.success(null);
    }

    @PostMapping("/register")
    public BaseResp<LoginData> register(@Valid @RequestBody RegisterReq req) {
        return BaseResp.success(userRegisterService.register(req));
    }

    @PostMapping("/login")
    public BaseResp<LoginData> login(@Valid @RequestBody LoginReq req) {
        return BaseResp.success(userLoginService.login(req));
    }
}
