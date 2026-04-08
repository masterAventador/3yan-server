package com.sanyan.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Push notification service stub.
 * Full implementation will be done in Task 11 (device token registration, APNs/FCM).
 */
@Slf4j
@Service
public class PushService {

    /**
     * Send a push notification to the given user.
     *
     * @param userId  target user ID
     * @param title   notification title
     * @param content notification body
     */
    public void sendPush(Long userId, String title, String content) {
        log.info("推送通知 → userId={}, title={}, content={}", userId, title, content);
        // TODO Task 11: integrate APNs / FCM
    }
}
