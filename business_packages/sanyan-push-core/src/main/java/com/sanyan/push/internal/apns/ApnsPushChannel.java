package com.sanyan.push.internal.apns;

import com.sanyan.push.PushChannel;
import com.sanyan.push.dto.DeviceTokenDto;
import com.sanyan.push.dto.PushPayload;
import com.sanyan.push.dto.PushResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * APNs（iOS）推送通道骨架（spec §8）。
 *
 * <p><b>本期边界：</b>只搭结构——{@link #sendToDevice} 返回 {@link PushResult#pending}
 * 占位，<b>不真发</b>。理由：APNs token-based auth 需要 Apple 下发的 {@code .p8} AuthKey，
 * 证书未到位前实例化 pushy {@code ApnsClient} 会失败。离线推送本期靠 L4 启动拉取兜底
 * （spec §7.1），不阻塞主流程。
 *
 * <p><b>实推待办（证书到位后）：</b>注入 {@link ApnsProperties} 构建 pushy
 * {@code ApnsClient}（{@code com.eatthepath:pushy} 0.15.4，已在 pom），
 * {@code sendToDevice} 改为真发 {@code SimpleApnsPushNotification} 并把投递结果映射为
 * {@link PushResult#sent()} / {@link PushResult#failed(String)}。
 *
 * <p>Spring 自动收集所有 {@link PushChannel} Bean 注入 {@link com.sanyan.push.internal.PushRouter}。
 */
@Component
@RequiredArgsConstructor
public class ApnsPushChannel implements PushChannel {

    /** 本期占位文案；实推接入后删除此分支。 */
    static final String PENDING_DETAIL = "APNs 实推待证书接入";

    static final String VENDOR_APNS = "apns";
    static final String PLATFORM_IOS = "ios";

    private final ApnsProperties properties;

    @Override
    public String vendor() {
        return VENDOR_APNS;
    }

    @Override
    public boolean supports(String platform) {
        return PLATFORM_IOS.equals(platform);
    }

    @Override
    public PushResult sendToDevice(DeviceTokenDto token, PushPayload payload) {
        // 本期不真发——证书到位后用 properties 构建 ApnsClient 并真正投递
        return PushResult.pending(PENDING_DETAIL);
    }
}
