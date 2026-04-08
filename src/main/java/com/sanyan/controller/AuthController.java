package com.sanyan.controller;

import com.sanyan.dto.ApiResponse;
import com.sanyan.dto.data.LoginData;
import com.sanyan.dto.req.LoginReq;
import com.sanyan.dto.req.PasswordResetReq;
import com.sanyan.dto.req.RegisterReq;
import com.sanyan.dto.req.SmsSendReq;
import com.sanyan.service.AuthService;
import com.sanyan.service.SmsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SmsService smsService;

    @PostMapping("/sms/send")
    public ApiResponse<Void> sendSms(@Valid @RequestBody SmsSendReq req) {
        smsService.sendCode(req.getPhone());
        return ApiResponse.ok();
    }

    @PostMapping("/register")
    public ApiResponse<LoginData> register(@Valid @RequestBody RegisterReq req) {
        try {
            return ApiResponse.ok(authService.register(req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/login")
    public ApiResponse<LoginData> login(@Valid @RequestBody LoginReq req) {
        try {
            return ApiResponse.ok(authService.login(req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/password/reset")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody PasswordResetReq req) {
        try {
            authService.resetPassword(req);
            return ApiResponse.ok();
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }
}
