package com.sanyan.user.internal.oauth;

import com.sanyan.common.cache.KvCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OauthChallengeServiceTest {

    @Mock
    private KvCache kvCache;

    @InjectMocks
    private OauthChallengeService service;

    @Test
    void issueNonce_shouldReturnNonBlankNonce() {
        String nonce = service.issueNonce();

        assertThat(nonce).isNotBlank();
    }

    @Test
    void issueNonce_shouldStoreNonceInKvCacheWithPrefixAndTtl() {
        String nonce = service.issueNonce();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(kvCache).set(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());

        assertThat(keyCaptor.getValue()).isEqualTo("oauth:nonce:" + nonce);
        assertThat(valueCaptor.getValue()).isEqualTo("1");
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofMinutes(5));
    }
}
