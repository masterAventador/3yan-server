package com.sanyan.push.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 设备推送 token（V11 device_tokens 表，Plan 4）。
 *
 * <p>客户端通过 {@code POST /api/devices/register} 上报；{@link com.sanyan.push.internal.PushRouter}
 * 投递离线推送时按 platform/vendor 路由到对应 {@link com.sanyan.push.PushChannel}。
 *
 * <p>无外键（spec §3.1）。唯一约束 (user_id, platform, vendor, token)：同一设备 token 重复注册
 * 走"已存在则置 active+刷新 last_seen"（{@link com.sanyan.push.api.PushApiImpl#registerDevice}）。
 */
@Data
@Entity
@Table(name = "device_tokens",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"user_id", "platform", "vendor", "token"}))
public class DeviceTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String platform;   // ios / android

    @Column(nullable = false, length = 20)
    private String vendor;     // apns / getui / jpush / ...

    @Column(nullable = false, length = 500)
    private String token;

    @Column(nullable = false)
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    /** 最后活跃时间。由写入方（注册/上报路径）手动 setLastSeen 维护，不用 @CreationTimestamp。 */
    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;
}
