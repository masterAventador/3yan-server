package com.sanyan.push.api;

import com.sanyan.push.PushApi;
import com.sanyan.push.dto.DeviceTokenDto;
import com.sanyan.push.dto.PushPayload;
import com.sanyan.push.dto.PushResult;
import com.sanyan.push.internal.DeviceTokenEntity;
import com.sanyan.push.internal.DeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * {@link PushApi} 在 push-core 的实现。
 *
 * <p>本期（spec §8）只实现设备注册 + 列举 active token；{@code pushToUser} 在 G7 委托
 * {@link com.sanyan.push.internal.PushRouter}（本 task 先占位）。
 *
 * <p>registerDevice 幂等：按唯一键 (user, platform, vendor, token) 找已有行——存在则置
 * active=true + 刷新 last_seen（设备重装/重登复用同一行），不存在则新建。
 */
@Service
@RequiredArgsConstructor
public class PushApiImpl implements PushApi {

    private final DeviceTokenRepository repository;

    @Override
    @Transactional
    public void registerDevice(Long userId, String platform, String vendor, String token) {
        DeviceTokenEntity entity = repository
                .findByUserIdAndPlatformAndVendorAndToken(userId, platform, vendor, token)
                .orElseGet(() -> {
                    DeviceTokenEntity fresh = new DeviceTokenEntity();
                    fresh.setUserId(userId);
                    fresh.setPlatform(platform);
                    fresh.setVendor(vendor);
                    fresh.setToken(token);
                    return fresh;
                });
        entity.setActive(true);
        entity.setLastSeen(Instant.now());
        repository.save(entity);
    }

    @Override
    public PushResult pushToUser(Long userId, PushPayload payload) {
        // G7 替换为委托 PushRouter
        throw new UnsupportedOperationException("pushToUser 由 G7 接入 PushRouter");
    }

    @Override
    public List<DeviceTokenDto> listActiveTokens(Long userId) {
        return repository.findByUserIdAndActiveTrue(userId).stream()
                .map(e -> new DeviceTokenDto(e.getUserId(), e.getPlatform(), e.getVendor(), e.getToken()))
                .toList();
    }
}
