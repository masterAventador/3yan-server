package com.sanyan.proactive.internal;

import com.sanyan.proactive.internal.fixtures.EventPendingTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProactiveClaimTransactionTest {

    @Mock EventPendingRepository repo;
    @Mock ProactiveDispatcher dispatcher;

    private ProactiveClaimTransaction claimer() {
        return new ProactiveClaimTransaction(repo, dispatcher);
    }

    @Test
    void claimAndDispatch_should_mark_processing_save_and_dispatch() {
        EventPendingEntity event = EventPendingTestFixtures.scheduled(
                1L, 99L, EventType.A_GREETING, Instant.now());
        when(repo.findDueForUpdate(any(), anyInt())).thenReturn(List.of(event));
        doNothing().when(dispatcher).dispatch(event);

        claimer().claimAndDispatch();

        // SKIP LOCKED 领取 → 标 PROCESSING + save（锁持有到事务提交）→ fire-and-forget dispatch
        assertThat(event.getStatus()).isEqualTo(EventStatus.PROCESSING);
        verify(repo).save(event);
        verify(dispatcher).dispatch(event);
    }

    @Test
    void claimAndDispatch_no_due_should_return_quietly() {
        when(repo.findDueForUpdate(any(), anyInt())).thenReturn(List.of());

        claimer().claimAndDispatch();

        verify(repo, org.mockito.Mockito.never()).save(any());
        verifyNoInteractions(dispatcher);
    }
}
