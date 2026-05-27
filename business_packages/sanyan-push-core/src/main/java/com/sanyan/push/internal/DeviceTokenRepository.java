package com.sanyan.push.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * device_tokens 仓储。
 * <ul>
 *   <li>{@code findByUserIdAndActiveTrue}：PushRouter 取用户所有 active 设备</li>
 *   <li>{@code findByUserIdAndPlatformAndVendorAndToken}：注册时按唯一键找已有行（幂等注册）</li>
 * </ul>
 */
public interface DeviceTokenRepository extends JpaRepository<DeviceTokenEntity, Long> {

    List<DeviceTokenEntity> findByUserIdAndActiveTrue(Long userId);

    Optional<DeviceTokenEntity> findByUserIdAndPlatformAndVendorAndToken(
            Long userId, String platform, String vendor, String token);
}
