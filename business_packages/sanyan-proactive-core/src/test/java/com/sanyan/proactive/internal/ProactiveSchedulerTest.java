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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProactiveSchedulerTest {

    @Mock EventPendingRepository repo;
    @Mock ProactiveDispatcher dispatcher;

    private ProactiveScheduler scheduler() {
        return new ProactiveScheduler(repo, dispatcher);
    }

    @Test
    void poll_should_mark_processing_dispatch_and_save() {
        EventPendingEntity event = EventPendingTestFixtures.scheduled(
                1L, 99L, EventType.A_GREETING, Instant.now());
        when(repo.findDueForUpdate(any(), anyInt())).thenReturn(List.of(event));
        doNothing().when(dispatcher).dispatch(event);

        scheduler().poll();

        // 标 PROCESSING（领取时）+ 最终 dispatch 后 save
        verify(dispatcher).dispatch(event);
        verify(repo, org.mockito.Mockito.atLeastOnce()).save(event);
    }

    @Test
    void poll_dispatch_failure_under_max_should_backoff_to_scheduled() {
        EventPendingEntity event = EventPendingTestFixtures.scheduled(
                1L, 99L, EventType.A_GREETING, Instant.now());
        event.setFailCount(0);
        when(repo.findDueForUpdate(any(), anyInt())).thenReturn(List.of(event));
        doThrow(new RuntimeException("generate boom")).when(dispatcher).dispatch(event);

        scheduler().poll();

        assertThat(event.getStatus()).isEqualTo(EventStatus.SCHEDULED);
        assertThat(event.getFailCount()).isEqualTo(1);
        assertThat(event.getLastError()).contains("generate boom");
        verify(repo, org.mockito.Mockito.atLeastOnce()).save(event);
    }

    @Test
    void poll_dispatch_failure_over_max_should_mark_failed() {
        EventPendingEntity event = EventPendingTestFixtures.scheduled(
                1L, 99L, EventType.A_GREETING, Instant.now());
        event.setFailCount(3); // 已失败 3 次，再失败 → 超限
        when(repo.findDueForUpdate(any(), anyInt())).thenReturn(List.of(event));
        doThrow(new RuntimeException("boom")).when(dispatcher).dispatch(event);

        scheduler().poll();

        assertThat(event.getStatus()).isEqualTo(EventStatus.FAILED);
        assertThat(event.getFailCount()).isEqualTo(4);
    }

    @Test
    void poll_should_swallow_repo_exception() {
        when(repo.findDueForUpdate(any(), anyInt())).thenThrow(new RuntimeException("db down"));

        // 不应抛出（整体 try-catch 兜底，照 RagIndexWorker）
        scheduler().poll();
    }
}
