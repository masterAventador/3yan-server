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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProactiveSchedulerTest {

    @Mock EventPendingRepository repo;
    @Mock ProactiveDispatcher dispatcher;

    private ProactiveScheduler scheduler() {
        return new ProactiveScheduler(repo, dispatcher);
    }

    @Test
    void poll_should_mark_processing_save_and_dispatch() {
        EventPendingEntity event = EventPendingTestFixtures.scheduled(
                1L, 99L, EventType.A_GREETING, Instant.now());
        when(repo.findDueForUpdate(any(), anyInt())).thenReturn(List.of(event));
        doNothing().when(dispatcher).dispatch(event);

        scheduler().poll();

        // 领取后标 PROCESSING + save，再 fire-and-forget 调 dispatch（@Async）。
        // 终态 save / 失败退避全部移入 dispatch，主循环不再做。
        assertThat(event.getStatus()).isEqualTo(EventStatus.PROCESSING);
        verify(repo).save(event);
        verify(dispatcher).dispatch(event);
    }

    @Test
    void poll_should_swallow_repo_exception() {
        when(repo.findDueForUpdate(any(), anyInt())).thenThrow(new RuntimeException("db down"));

        // 不应抛出（整体 try-catch 兜底，照 RagIndexWorker）
        scheduler().poll();
    }
}
