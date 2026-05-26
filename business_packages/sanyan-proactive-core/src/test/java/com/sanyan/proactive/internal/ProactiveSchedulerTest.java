package com.sanyan.proactive.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProactiveSchedulerTest {

    @Mock ProactiveClaimTransaction claimTransaction;

    private ProactiveScheduler scheduler() {
        return new ProactiveScheduler(claimTransaction);
    }

    @Test
    void poll_should_delegate_to_claim_transaction() {
        doNothing().when(claimTransaction).claimAndDispatch();

        scheduler().poll();

        // poll 只做兜底 try-catch + 委托；领取/标 PROCESSING/dispatch 全在事务内层 bean。
        verify(claimTransaction).claimAndDispatch();
    }

    @Test
    void poll_should_swallow_transaction_exception() {
        // 事务内层抛出（含 commit 时的 UnexpectedRollbackException）由 poll 的 try-catch 兜住，不外泄给调度器
        doThrow(new RuntimeException("db down")).when(claimTransaction).claimAndDispatch();

        scheduler().poll();
    }
}
