package com.sanyan.push.internal.fixtures;

import com.sanyan.push.internal.DeviceTokenEntity;

import java.time.Instant;

/**
 * DeviceTokenEntity Object Mother（java-backend-business-layer.md §5.2）。
 */
public final class DeviceTokenTestFixtures {

    private DeviceTokenTestFixtures() {}

    public static DeviceTokenEntity iosApns(Long userId, String token) {
        return build(userId, "ios", "apns", token);
    }

    public static DeviceTokenEntity androidGetui(Long userId, String token) {
        return build(userId, "android", "getui", token);
    }

    private static DeviceTokenEntity build(Long userId, String platform, String vendor, String token) {
        DeviceTokenEntity e = new DeviceTokenEntity();
        e.setUserId(userId);
        e.setPlatform(platform);
        e.setVendor(vendor);
        e.setToken(token);
        e.setActive(true);
        e.setLastSeen(Instant.now());
        return e;
    }
}
