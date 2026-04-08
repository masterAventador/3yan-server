package com.sanyan.controller;

import com.sanyan.dto.ApiResponse;
import com.sanyan.service.PushService;
import com.sanyan.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
public class DeviceController {

    private final PushService pushService;
    private final JwtUtil jwtUtil;

    @PostMapping("/push-token")
    public ApiResponse<Void> updatePushToken(HttpServletRequest request,
                                              @RequestBody PushTokenReq req) {
        Long userId = getUserId(request);
        pushService.updatePushToken(userId, req.getPushToken(), req.getDeviceType());
        return ApiResponse.ok();
    }

    private Long getUserId(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        return jwtUtil.parseUserId(token);
    }

    @Data
    public static class PushTokenReq {
        private String pushToken;
        private String deviceType;
    }
}
