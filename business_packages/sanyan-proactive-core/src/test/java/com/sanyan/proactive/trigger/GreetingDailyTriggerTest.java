package com.sanyan.proactive.trigger;

import com.sanyan.character.CharacterApi;
import com.sanyan.character.dto.RelationshipDto;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GreetingDailyTriggerTest {

    @Mock CharacterApi characterApi;
    @Mock EventPendingRepository eventRepo;
    ProactiveProperties props;
    GreetingDailyTrigger trigger;

    private static final Long CHARACTER_ID = 1L;

    @BeforeEach
    void setup() {
        props = ProactivePropertiesFixtures.defaults();
        trigger = new GreetingDailyTrigger(characterApi, eventRepo, props);
        when(characterApi.listActiveRelationshipUserIds(CHARACTER_ID)).thenReturn(List.of(100L));
    }

    private void stubStage(long userId, int stage) {
        when(characterApi.findOrCreateRelationship(userId, CHARACTER_ID))
                .thenReturn(new RelationshipDto(userId, CHARACTER_ID, 0, stage, "x", 100, 0.0));
    }

    // ── 早安 ──────────────────────────────────────────────────────────────────

    @Test
    void morning_should_enqueue_a_greeting_for_stage2_where_morning_enabled() {
        stubStage(100L, 2);   // stage 2: morning=true

        trigger.scheduleMorning();

        ArgumentCaptor<EventPendingEntity> cap = ArgumentCaptor.forClass(EventPendingEntity.class);
        verify(eventRepo).save(cap.capture());
        EventPendingEntity e = cap.getValue();
        assertThat(e.getEventType()).isEqualTo(EventType.A_GREETING);
        assertThat(e.getUserId()).isEqualTo(100L);
        assertThat(e.getPayload()).contains("morning");
    }

    @Test
    void morning_should_not_enqueue_for_stage0_where_morning_disabled() {
        stubStage(100L, 0);   // stage 0: morning=false

        trigger.scheduleMorning();

        verify(eventRepo, never()).save(any());
    }

    @Test
    void morning_should_not_enqueue_for_stage1_where_morning_disabled() {
        stubStage(100L, 1);   // stage 1: morning=false（只开了 night）

        trigger.scheduleMorning();

        verify(eventRepo, never()).save(any());
    }

    // ── 晚安 ──────────────────────────────────────────────────────────────────

    @Test
    void night_should_enqueue_for_stage1_where_night_enabled() {
        stubStage(100L, 1);   // stage 1: night=true

        trigger.scheduleNight();

        ArgumentCaptor<EventPendingEntity> cap = ArgumentCaptor.forClass(EventPendingEntity.class);
        verify(eventRepo).save(cap.capture());
        assertThat(cap.getValue().getPayload()).contains("night");
    }

    @Test
    void night_should_not_enqueue_for_stage0_where_night_disabled() {
        stubStage(100L, 0);   // stage 0: night=false

        trigger.scheduleNight();

        verify(eventRepo, never()).save(any());
    }

    // ── null guard：scenesByStage 未配置的 stage ─────────────────────────────

    @Test
    void morning_should_not_enqueue_when_stage_not_configured_in_scenesByStage() {
        stubStage(100L, 99);  // stage 99: scenesByStage 中不存在，get() 返回 null

        trigger.scheduleMorning();

        verify(eventRepo, never()).save(any());
    }

    // ── payload 字段验证 ──────────────────────────────────────────────────────

    @Test
    void enqueued_entity_should_have_correct_event_type_user_id_and_character_id() {
        stubStage(100L, 2);

        trigger.scheduleMorning();

        ArgumentCaptor<EventPendingEntity> cap = ArgumentCaptor.forClass(EventPendingEntity.class);
        verify(eventRepo).save(cap.capture());
        EventPendingEntity e = cap.getValue();
        assertThat(e.getEventType()).isEqualTo(EventType.A_GREETING);
        assertThat(e.getUserId()).isEqualTo(100L);
        assertThat(e.getCharacterId()).isEqualTo(CHARACTER_ID);
        assertThat(e.getScheduledAt()).isNotNull();
    }

    // ── scatter 分散：scheduledAt 在 now..now+30min 范围内 ─────────────────────

    @Test
    void scheduledAt_should_be_after_now_and_within_scatter_window() {
        stubStage(100L, 2);

        java.time.Instant before = java.time.Instant.now();
        trigger.scheduleMorning();
        java.time.Instant after = java.time.Instant.now()
                .plus(props.getScatterWindowMinutes(), java.time.temporal.ChronoUnit.MINUTES);

        ArgumentCaptor<EventPendingEntity> cap = ArgumentCaptor.forClass(EventPendingEntity.class);
        verify(eventRepo).save(cap.capture());
        java.time.Instant scheduledAt = cap.getValue().getScheduledAt();
        assertThat(scheduledAt).isAfterOrEqualTo(before);
        assertThat(scheduledAt).isBeforeOrEqualTo(after);
    }
}
