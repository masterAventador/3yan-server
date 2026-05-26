package com.sanyan.push.api;

import com.sanyan.push.dto.DeviceTokenDto;
import com.sanyan.push.internal.DeviceTokenEntity;
import com.sanyan.push.internal.DeviceTokenRepository;
import com.sanyan.push.internal.fixtures.DeviceTokenTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushApiImplTest {

    @Mock DeviceTokenRepository repository;
    @InjectMocks PushApiImpl api;

    @Test
    void registerDevice_should_create_new_when_not_exists() {
        when(repository.findByUserIdAndPlatformAndVendorAndToken(7L, "ios", "apns", "tok-1"))
                .thenReturn(Optional.empty());

        api.registerDevice(7L, "ios", "apns", "tok-1");

        ArgumentCaptor<DeviceTokenEntity> captor = ArgumentCaptor.forClass(DeviceTokenEntity.class);
        verify(repository).save(captor.capture());
        DeviceTokenEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getPlatform()).isEqualTo("ios");
        assertThat(saved.getVendor()).isEqualTo("apns");
        assertThat(saved.getToken()).isEqualTo("tok-1");
        assertThat(saved.getActive()).isTrue();
        assertThat(saved.getLastSeen()).isNotNull();
    }

    @Test
    void registerDevice_should_reactivate_existing_and_refresh_last_seen() {
        DeviceTokenEntity existing = DeviceTokenTestFixtures.iosApns(7L, "tok-1");
        existing.setId(42L);
        existing.setActive(false);
        when(repository.findByUserIdAndPlatformAndVendorAndToken(7L, "ios", "apns", "tok-1"))
                .thenReturn(Optional.of(existing));

        api.registerDevice(7L, "ios", "apns", "tok-1");

        ArgumentCaptor<DeviceTokenEntity> captor = ArgumentCaptor.forClass(DeviceTokenEntity.class);
        verify(repository).save(captor.capture());
        DeviceTokenEntity saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(42L);          // 复用同一行，不新建
        assertThat(saved.getActive()).isTrue();
        assertThat(saved.getLastSeen()).isNotNull();
    }

    @Test
    void listActiveTokens_should_map_entities_to_dto() {
        DeviceTokenEntity a = DeviceTokenTestFixtures.iosApns(7L, "tok-a");
        DeviceTokenEntity b = DeviceTokenTestFixtures.androidGetui(7L, "tok-b");
        when(repository.findByUserIdAndActiveTrue(7L)).thenReturn(List.of(a, b));

        List<DeviceTokenDto> dtos = api.listActiveTokens(7L);

        assertThat(dtos).hasSize(2);
        assertThat(dtos).extracting(DeviceTokenDto::userId).containsOnly(7L);
        assertThat(dtos).extracting(DeviceTokenDto::vendor).containsExactlyInAnyOrder("apns", "getui");
        assertThat(dtos).extracting(DeviceTokenDto::token).containsExactlyInAnyOrder("tok-a", "tok-b");
    }

    @Test
    void listActiveTokens_should_return_empty_when_none() {
        when(repository.findByUserIdAndActiveTrue(7L)).thenReturn(List.of());
        assertThat(api.listActiveTokens(7L)).isEmpty();
        verify(repository, never()).save(any());
    }
}
