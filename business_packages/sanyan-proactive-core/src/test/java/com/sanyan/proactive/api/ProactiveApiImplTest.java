package com.sanyan.proactive.api;

import com.sanyan.common.error.BusinessException;
import com.sanyan.proactive.dto.ProactiveTriggerResult;
import com.sanyan.proactive.internal.EventPendingEntity;
import com.sanyan.proactive.internal.EventPendingRepository;
import com.sanyan.proactive.internal.EventStatus;
import com.sanyan.proactive.internal.EventType;
import com.sanyan.proactive.internal.ProactiveErrCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProactiveApiImplTest {

    @Mock EventPendingRepository repo;
    @InjectMocks ProactiveApiImpl api;

    @Test
    void triggerNow_should_insert_scheduled_event_and_return_scheduled_true() {
        when(repo.save(any(EventPendingEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ProactiveTriggerResult result = api.triggerNow(1L, 99L, "a_greeting");

        assertThat(result.scheduled()).isTrue();
        ArgumentCaptor<EventPendingEntity> cap = ArgumentCaptor.forClass(EventPendingEntity.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getUserId()).isEqualTo(1L);
        assertThat(cap.getValue().getCharacterId()).isEqualTo(99L);
        assertThat(cap.getValue().getEventType()).isEqualTo(EventType.A_GREETING);
        assertThat(cap.getValue().getStatus()).isEqualTo(EventStatus.SCHEDULED);
        assertThat(cap.getValue().getScheduledAt()).isNotNull();
    }

    @Test
    void triggerNow_should_accept_uppercase_event_type() {
        when(repo.save(any(EventPendingEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ProactiveTriggerResult result = api.triggerNow(1L, 99L, "D_EMOTION_CARE");

        assertThat(result.scheduled()).isTrue();
    }

    @Test
    void triggerNow_should_throw_on_unknown_event_type() {
        assertThatThrownBy(() -> api.triggerNow(1L, 99L, "x_unknown"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrCode())
                        .isEqualTo(ProactiveErrCode.PROACTIVE_EVENT_NOT_FOUND));
    }
}
