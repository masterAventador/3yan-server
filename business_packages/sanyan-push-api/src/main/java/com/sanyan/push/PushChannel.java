package com.sanyan.push;

import com.sanyan.push.dto.DeviceTokenDto;
import com.sanyan.push.dto.PushPayload;
import com.sanyan.push.dto.PushResult;

/** 单一推送通道（APNs / 个推 / ...）。本期只 APNs 骨架。 */
public interface PushChannel {
    String vendor();                       // "apns" / "getui"
    boolean supports(String platform);     // "ios" / "android"
    PushResult sendToDevice(DeviceTokenDto token, PushPayload payload);
}
