package com.sanyan.common.ws;

import com.sanyan.common.cache.KvCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LastActiveTrackerTest {

    @Mock KvCache kvCache;

    @Test
    void touch_setsLastActiveWithUserKeyAnd30dTtl() {
        LastActiveTracker tracker = new LastActiveTracker(kvCache);

        tracker.touch(42L);

        ArgumentCaptor<String> valueCap = ArgumentCaptor.forClass(String.class);
        verify(kvCache).set(org.mockito.ArgumentMatchers.eq("user:last_active:42"),
                valueCap.capture(),
                org.mockito.ArgumentMatchers.eq(Duration.ofDays(30)));
        assertThat(Instant.parse(valueCap.getValue())).isNotNull();
    }

    @Test
    void lastActiveAt_parsesStoredTimestamp() {
        Instant now = Instant.parse("2026-05-27T09:00:00Z");
        when(kvCache.get("user:last_active:42")).thenReturn(now.toString());
        LastActiveTracker tracker = new LastActiveTracker(kvCache);

        Optional<Instant> result = tracker.lastActiveAt(42L);

        assertThat(result).contains(now);
    }

    @Test
    void lastActiveAt_returnsEmptyWhenKeyMissing() {
        when(kvCache.get("user:last_active:42")).thenReturn(null);
        LastActiveTracker tracker = new LastActiveTracker(kvCache);

        assertThat(tracker.lastActiveAt(42L)).isEmpty();
    }
}
