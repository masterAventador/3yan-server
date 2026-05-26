package com.sanyan.push;

import com.sanyan.push.dto.DeviceTokenDto;
import com.sanyan.push.dto.PushPayload;
import com.sanyan.push.dto.PushResult;
import java.util.List;

/** 推送域对内契约：注册设备 token + 给用户推送。 */
public interface PushApi {
    void registerDevice(Long userId, String platform, String vendor, String token);
    /** 给用户所有 active 设备推送；本期 L3 实推为占位。 */
    PushResult pushToUser(Long userId, PushPayload payload);
    List<DeviceTokenDto> listActiveTokens(Long userId);
}
