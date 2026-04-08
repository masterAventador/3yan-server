package com.sanyan.service;

import com.sanyan.entity.UserToken;
import com.sanyan.repository.UserTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PushServiceTest {

    @Mock private UserTokenRepository tokenRepository;
    @InjectMocks private PushService pushService;

    @Test
    void shouldUpdatePushToken() {
        UserToken existing = new UserToken();
        existing.setUserId(1L);
        existing.setPushToken("old_token");
        when(tokenRepository.findByUserId(1L)).thenReturn(Optional.of(existing));

        pushService.updatePushToken(1L, "new_token", "ios");

        verify(tokenRepository).save(argThat(t -> "new_token".equals(t.getPushToken())));
    }

    @Test
    void shouldCreateTokenIfNotExists() {
        when(tokenRepository.findByUserId(2L)).thenReturn(Optional.empty());

        pushService.updatePushToken(2L, "token_123", "android");

        verify(tokenRepository).save(argThat(t ->
                t.getUserId().equals(2L) && "token_123".equals(t.getPushToken())));
    }
}
