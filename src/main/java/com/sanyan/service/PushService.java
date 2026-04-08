package com.sanyan.service;

import com.sanyan.entity.UserToken;
import com.sanyan.repository.UserTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PushService {

    private final UserTokenRepository tokenRepository;

    /**
     * Update or create the push token for a user device.
     */
    public void updatePushToken(Long userId, String pushToken, String deviceType) {
        UserToken record = tokenRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserToken t = new UserToken();
                    t.setUserId(userId);
                    return t;
                });
        record.setPushToken(pushToken);
        record.setDeviceType(deviceType);
        tokenRepository.save(record);
        log.info("设备 Token 已更新 → userId={}, deviceType={}", userId, deviceType);
    }

    /**
     * Send a push notification to the given user (log-only for MVP).
     */
    public void sendPush(Long userId, String content) {
        tokenRepository.findByUserId(userId).ifPresentOrElse(
                t -> log.info("推送通知 → userId={}, pushToken={}, content={}",
                        userId, t.getPushToken(), content),
                () -> log.warn("推送失败：用户 {} 无设备 Token", userId)
        );
    }
}
