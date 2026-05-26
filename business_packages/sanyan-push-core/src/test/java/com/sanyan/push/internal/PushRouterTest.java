package com.sanyan.push.internal;

import com.sanyan.push.PushChannel;
import com.sanyan.push.dto.DeviceTokenDto;
import com.sanyan.push.dto.PushPayload;
import com.sanyan.push.dto.PushResult;
import com.sanyan.push.internal.fixtures.DeviceTokenTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushRouterTest {

    @Mock DeviceTokenRepository repository;
    @Mock PushChannel apnsChannel;

    private final PushPayload payload = new PushPayload("小婉", "在想你", 1L);

    private PushRouter router() {
        return new PushRouter(List.of(apnsChannel), repository);
    }

    @Test
    void pushToUser_should_route_ios_token_to_supporting_channel() {
        when(repository.findByUserIdAndActiveTrue(7L))
                .thenReturn(List.of(DeviceTokenTestFixtures.iosApns(7L, "tok-1")));
        when(apnsChannel.supports("ios")).thenReturn(true);
        when(apnsChannel.sendToDevice(any(DeviceTokenDto.class), any())).thenReturn(PushResult.sent());

        PushResult result = router().pushToUser(7L, payload);

        assertThat(result.status()).isEqualTo("SENT");
        verify(apnsChannel).sendToDevice(any(DeviceTokenDto.class), any());
    }

    @Test
    void pushToUser_should_return_pending_when_no_active_token() {
        when(repository.findByUserIdAndActiveTrue(7L)).thenReturn(List.of());

        PushResult result = router().pushToUser(7L, payload);

        assertThat(result.status()).isEqualTo("PENDING");
        verify(apnsChannel, never()).sendToDevice(any(), any());
    }

    @Test
    void pushToUser_should_skip_token_with_no_supporting_channel() {
        when(repository.findByUserIdAndActiveTrue(7L))
                .thenReturn(List.of(DeviceTokenTestFixtures.androidGetui(7L, "tok-android")));
        when(apnsChannel.supports("android")).thenReturn(false);

        PushResult result = router().pushToUser(7L, payload);

        // 有 active token 但无通道能处理 android → pending，detail 区分于"无设备"
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.detail()).isEqualTo(PushRouter.NO_CHANNEL_DETAIL);
        verify(apnsChannel, never()).sendToDevice(any(), any());
    }

    @Test
    void pushToUser_should_aggregate_sent_over_pending() {
        when(repository.findByUserIdAndActiveTrue(7L)).thenReturn(List.of(
                DeviceTokenTestFixtures.iosApns(7L, "tok-1"),
                DeviceTokenTestFixtures.iosApns(7L, "tok-2")));
        when(apnsChannel.supports("ios")).thenReturn(true);
        when(apnsChannel.sendToDevice(any(DeviceTokenDto.class), any()))
                .thenReturn(PushResult.pending("待证书"))
                .thenReturn(PushResult.sent());

        PushResult result = router().pushToUser(7L, payload);

        // 一条 SENT 即整体 SENT
        assertThat(result.status()).isEqualTo("SENT");
    }

    @Test
    void pushToUser_should_return_failed_when_only_failures() {
        when(repository.findByUserIdAndActiveTrue(7L))
                .thenReturn(List.of(DeviceTokenTestFixtures.iosApns(7L, "tok-1")));
        when(apnsChannel.supports("ios")).thenReturn(true);
        when(apnsChannel.sendToDevice(any(DeviceTokenDto.class), any()))
                .thenReturn(PushResult.failed("BadDeviceToken"));

        PushResult result = router().pushToUser(7L, payload);

        assertThat(result.status()).isEqualTo("FAILED");
    }
}
