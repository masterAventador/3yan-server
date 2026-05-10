package com.sanyan.controller;

import com.sanyan.auth.LoginUser;
import com.sanyan.dto.ApiResponse;
import com.sanyan.service.PushService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
public class DeviceController {

    private final PushService pushService;

    @PostMapping("/push-token")
    public ApiResponse<Void> updatePushToken(@LoginUser Long userId,
                                             @RequestBody PushTokenReq req) {
        pushService.updatePushToken(userId, req.getPushToken(), req.getDeviceType());
        return ApiResponse.ok();
    }

    @Data
    public static class PushTokenReq {
        private String pushToken;
        private String deviceType;
    }
}
