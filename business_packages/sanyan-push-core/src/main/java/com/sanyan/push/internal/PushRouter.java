package com.sanyan.push.internal;

import com.sanyan.push.PushChannel;
import com.sanyan.push.dto.DeviceTokenDto;
import com.sanyan.push.dto.PushPayload;
import com.sanyan.push.dto.PushResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 推送路由（spec §8）：按设备 platform 把推送分发到对应 {@link PushChannel}，汇总投递结果。
 *
 * <p>Spring 自动注入所有 {@link PushChannel} Bean（本期仅 {@code ApnsPushChannel}）。
 * 用户多设备时所有 active token 逐一尝试，找第一个 {@code supports(platform)} 的 channel 投递。
 *
 * <p>汇总规则（多设备多结果归一为单个 {@link PushResult}）：
 * <ul>
 *   <li>无 active token → PENDING（"无可推送设备"）</li>
 *   <li>任一设备 SENT → 整体 SENT</li>
 *   <li>无 SENT 但有 FAILED → 整体 FAILED（detail 取首个失败原因）</li>
 *   <li>其余（全 PENDING / 无可处理通道）→ PENDING</li>
 * </ul>
 *
 * <p>本期 sendToDevice 多为 PENDING（ApnsPushChannel 占位），离线推送靠 L4 启动拉取兜底。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PushRouter {

    static final String NO_DEVICE_DETAIL = "无可推送设备";
    static final String NO_CHANNEL_DETAIL = "无可处理该平台的推送通道";

    private final List<PushChannel> channels;
    private final DeviceTokenRepository repository;

    public PushResult pushToUser(Long userId, PushPayload payload) {
        List<DeviceTokenEntity> activeTokens = repository.findByUserIdAndActiveTrue(userId);
        if (activeTokens.isEmpty()) {
            return PushResult.pending(NO_DEVICE_DETAIL);
        }

        boolean anySent = false;
        String firstFailure = null;
        for (DeviceTokenEntity tokenEntity : activeTokens) {
            PushChannel channel = findChannel(tokenEntity.getPlatform());
            if (channel == null) {
                log.warn("无可处理 platform={} 的推送通道，跳过 token id={}",
                        tokenEntity.getPlatform(), tokenEntity.getId());
                continue;
            }
            DeviceTokenDto dto = new DeviceTokenDto(
                    tokenEntity.getUserId(), tokenEntity.getPlatform(),
                    tokenEntity.getVendor(), tokenEntity.getToken());
            PushResult result = channel.sendToDevice(dto, payload);
            if ("SENT".equals(result.status())) {
                anySent = true;
            } else if ("FAILED".equals(result.status()) && firstFailure == null) {
                firstFailure = result.detail();
            }
        }

        if (anySent) {
            return PushResult.sent();
        }
        if (firstFailure != null) {
            return PushResult.failed(firstFailure);
        }
        // 有 active token 但没有任何 channel 能处理对应平台
        return PushResult.pending(NO_CHANNEL_DETAIL);
    }

    private PushChannel findChannel(String platform) {
        return channels.stream()
                .filter(c -> c.supports(platform))
                .findFirst()
                .orElse(null);
    }
}
