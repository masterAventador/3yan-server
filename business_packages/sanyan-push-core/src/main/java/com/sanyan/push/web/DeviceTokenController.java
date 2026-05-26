package com.sanyan.push.web;

import com.sanyan.common.auth.LoginUser;
import com.sanyan.common.web.BaseResp;
import com.sanyan.push.PushApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备 token 注册接口（Plan 4，spec §8）。客户端拿到 APNs / 厂商 token 后上报，
 * 供离线推送（L3）路由。本期接口可用，实推待证书（{@code ApnsPushChannel} 占位）。
 */
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final PushApi pushApi;

    @PostMapping("/register")
    public BaseResp<Void> register(@LoginUser Long userId, @Valid @RequestBody RegisterDeviceReq req) {
        pushApi.registerDevice(userId, req.getPlatform(), req.getVendor(), req.getToken());
        return BaseResp.success(null);
    }

    /** 注册设备请求体（单接口专用，与 Controller 同文件，java-backend §3.2）。 */
    @Data
    public static class RegisterDeviceReq {
        @NotBlank
        private String platform;   // ios / android
        @NotBlank
        private String vendor;     // apns / getui / ...
        @NotBlank
        private String token;
    }
}
