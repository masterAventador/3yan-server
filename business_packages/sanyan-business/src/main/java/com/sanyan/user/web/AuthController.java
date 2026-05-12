package com.sanyan.user.web;

import com.sanyan.common.error.BusinessException;
import com.sanyan.dto.ApiResponse; // TODO M3.6: 替换为 BaseResp
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
    public ApiResponse<Void> sendSms(@Valid @RequestBody SmsSendReq req) {
        smsCodeSendService.sendCode(req.getPhone());
        return ApiResponse.ok();
    }

    @PostMapping("/register")
    public ApiResponse<LoginData> register(@Valid @RequestBody RegisterReq req) {
        try {
            return ApiResponse.ok(userRegisterService.register(req));
        } catch (BusinessException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/login")
    public ApiResponse<LoginData> login(@Valid @RequestBody LoginReq req) {
        try {
            return ApiResponse.ok(userLoginService.login(req));
        } catch (BusinessException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }
}
