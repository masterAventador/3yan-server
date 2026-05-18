package com.sanyan.character.internal.intimacy;

import com.sanyan.character.internal.CharacterErrCode;
import com.sanyan.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 测试 {@link IntimacyRecordService#recordEvent} 的 retry 行为。
 *
 * <p>只 mock {@link IntimacyRecordTransaction}，验证：
 * <ul>
 *   <li>正常路径：第一次 doRecord 成功直接返回</li>
 *   <li>乐观锁路径：OptimisticLockingFailureException 时 retry 3 次，3 次后抛 BusinessException</li>
 *   <li>中途成功：第 2 次才成功时整体成功，不抛异常</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class IntimacyRecordServiceTest {

    @Mock IntimacyRecordTransaction tx;
    @InjectMocks IntimacyRecordService service;

    @Test
    void should_succeed_on_first_attempt() {
        // doRecord 不抛异常（默认 void mock 行为）
        service.recordEvent(IntimacyEvent.messageSent(1L, 1L));

        verify(tx, times(1)).doRecord(any());
    }

    @Test
    void should_throw_business_exception_after_retry_exhausted() {
        doThrow(new OptimisticLockingFailureException("lock"))
                .when(tx).doRecord(any());

        assertThatThrownBy(() -> service.recordEvent(IntimacyEvent.messageSent(1L, 1L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errCode", CharacterErrCode.INTIMACY_CONCURRENT_UPDATE);

        // 应该 retry 3 次
        verify(tx, times(3)).doRecord(any());
    }

    @Test
    void should_succeed_when_second_attempt_succeeds() {
        doThrow(new OptimisticLockingFailureException("lock"))
                .doNothing()
                .when(tx).doRecord(any());

        // 第 2 次成功，整体不抛异常
        service.recordEvent(IntimacyEvent.messageSent(1L, 1L));

        verify(tx, times(2)).doRecord(any());
    }
}
