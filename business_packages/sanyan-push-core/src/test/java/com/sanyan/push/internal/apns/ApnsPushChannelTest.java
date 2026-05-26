package com.sanyan.push.internal.apns;

import com.sanyan.push.dto.DeviceTokenDto;
import com.sanyan.push.dto.PushPayload;
import com.sanyan.push.dto.PushResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApnsPushChannelTest {

    private final ApnsPushChannel channel = new ApnsPushChannel(new ApnsProperties());

    @Test
    void vendor_should_be_apns() {
        assertThat(channel.vendor()).isEqualTo("apns");
    }

    @Test
    void supports_should_be_true_only_for_ios() {
        assertThat(channel.supports("ios")).isTrue();
        assertThat(channel.supports("android")).isFalse();
        assertThat(channel.supports(null)).isFalse();
    }

    @Test
    void sendToDevice_should_return_pending_placeholder_this_phase() {
        DeviceTokenDto token = new DeviceTokenDto(7L, "ios", "apns", "tok-1");
        PushPayload payload = new PushPayload("小婉", "在想你哦", 100L);

        PushResult result = channel.sendToDevice(token, payload);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.detail()).contains("证书");
    }

    @Test
    void properties_should_bind_fields() {
        ApnsProperties props = new ApnsProperties();
        props.setP8("AuthKey_ABC.p8");
        props.setKeyId("KEY123");
        props.setTeamId("TEAM456");
        props.setTopic("com.sanyan.app");

        assertThat(props.getP8()).isEqualTo("AuthKey_ABC.p8");
        assertThat(props.getKeyId()).isEqualTo("KEY123");
        assertThat(props.getTeamId()).isEqualTo("TEAM456");
        assertThat(props.getTopic()).isEqualTo("com.sanyan.app");
    }
}
