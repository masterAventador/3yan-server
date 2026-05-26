package com.sanyan.proactive.trigger;

import com.sanyan.character.CharacterApi;
import com.sanyan.character.dto.RelationshipDto;
import com.sanyan.common.cache.KvCache;
import com.sanyan.common.ws.LastActiveTracker;
import com.sanyan.proactive.internal.EventPendingEntity;
import com.sanyan.proactive.internal.EventPendingRepository;
import com.sanyan.proactive.internal.EventType;
import com.sanyan.proactive.internal.ProactiveProperties;
import com.sanyan.proactive.internal.fixtures.ProactivePropertiesFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecallTriggerTest {

    @Mock CharacterApi characterApi;
    @Mock EventPendingRepository eventRepo;
    @Mock LastActiveTracker lastActiveTracker;
    @Mock KvCache kvCache;
    ProactiveProperties props;
    RecallTrigger trigger;

    private static final Long CHARACTER_ID = 1L;
    private static final Long USER = 100L;

    @BeforeEach
    void setup() {
        props = ProactivePropertiesFixtures.defaults();   // thresholdsHours = [24,72,168]
        trigger = new RecallTrigger(characterApi, eventRepo, lastActiveTracker, kvCache, props);
        when(characterApi.listActiveRelationshipUserIds(CHARACTER_ID)).thenReturn(List.of(USER));
        // stage 2 recall=true（默认所有 stage recall=true）
        lenient().when(characterApi.findOrCreateRelationship(USER, CHARACTER_ID))
                .thenReturn(new RelationshipDto(USER, CHARACTER_ID, 0, 2, "x", 100, 0.0));
        lenient().when(kvCache.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
    }

    private void offlineHours(long hours) {
        when(lastActiveTracker.lastActiveAt(USER))
                .thenReturn(Optional.of(Instant.now().minus(Duration.ofHours(hours))));
    }

    @Test
    void offline_25h_should_enqueue_recall_level0() {
        offlineHours(25);

        trigger.scanAndEnqueue();

        ArgumentCaptor<EventPendingEntity> cap = ArgumentCaptor.forClass(EventPendingEntity.class);
        verify(eventRepo).save(cap.capture());
        assertThat(cap.getValue().getEventType()).isEqualTo(EventType.B_RECALL);
        assertThat(cap.getValue().getPayload()).contains("\"escalationLevel\":0");
        verify(kvCache).setIfAbsent(eq("proactive:recall:100:0"), eq("1"), any(Duration.class));
    }

    @Test
    void offline_8d_should_enqueue_recall_level2() {
        offlineHours(24 * 8);

        trigger.scanAndEnqueue();

        ArgumentCaptor<EventPendingEntity> cap = ArgumentCaptor.forClass(EventPendingEntity.class);
        verify(eventRepo).save(cap.capture());
        assertThat(cap.getValue().getPayload()).contains("\"escalationLevel\":2");
    }

    @Test
    void offline_under_first_threshold_should_not_enqueue() {
        offlineHours(10);

        trigger.scanAndEnqueue();

        verify(eventRepo, never()).save(any());
    }

    @Test
    void should_not_enqueue_when_dedup_key_already_present() {
        offlineHours(25);
        when(kvCache.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        trigger.scanAndEnqueue();

        verify(eventRepo, never()).save(any());
    }

    @Test
    void should_not_enqueue_when_never_active() {
        when(lastActiveTracker.lastActiveAt(USER)).thenReturn(Optional.empty());

        trigger.scanAndEnqueue();

        verify(eventRepo, never()).save(any());
    }
}
