package com.sanyan.proactive.trigger;

import com.sanyan.memory.event.MemoryItemScheduledEvent;
import com.sanyan.proactive.internal.EventPendingEntity;
import com.sanyan.proactive.internal.EventPendingRepository;
import com.sanyan.proactive.internal.EventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MemoryItemScheduledListenerTest {

    @Mock EventPendingRepository eventRepo;
    @InjectMocks MemoryItemScheduledListener listener;

    @Test
    void plan_event_should_enqueue_c_event_followup_at_salient_at() {
        Instant salient = Instant.now().plusSeconds(3600);
        listener.onMemoryItemScheduled(
                new MemoryItemScheduledEvent(7L, 1L, 1L, "PLAN_EVENT", salient));

        ArgumentCaptor<EventPendingEntity> cap = ArgumentCaptor.forClass(EventPendingEntity.class);
        verify(eventRepo).save(cap.capture());
        EventPendingEntity e = cap.getValue();
        assertThat(e.getEventType()).isEqualTo(EventType.C_EVENT_FOLLOWUP);
        assertThat(e.getScheduledAt()).isEqualTo(salient);
        assertThat(e.getPayload()).contains("\"memoryItemId\":7");
    }

    @Test
    void emotion_should_enqueue_d_emotion_care() {
        listener.onMemoryItemScheduled(
                new MemoryItemScheduledEvent(9L, 1L, 1L, "EMOTION", Instant.now()));

        ArgumentCaptor<EventPendingEntity> cap = ArgumentCaptor.forClass(EventPendingEntity.class);
        verify(eventRepo).save(cap.capture());
        assertThat(cap.getValue().getEventType()).isEqualTo(EventType.D_EMOTION_CARE);
    }

    @Test
    void promise_should_not_enqueue() {
        listener.onMemoryItemScheduled(
                new MemoryItemScheduledEvent(11L, 1L, 1L, "PROMISE", Instant.now()));

        verify(eventRepo, never()).save(any());
    }
}
