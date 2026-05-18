package com.sanyan.character.internal.intimacy;

import com.sanyan.character.internal.CharacterErrCode;
import com.sanyan.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 亲密度涨分核心入口（事务外的 retry 控制层）。
 *
 * <p>事务边界拆分：
 * <ul>
 *   <li>本类 {@code recordEvent} <b>无 @Transactional</b>，只负责 retry 3 次</li>
 *   <li>真正的写操作委托给 {@link IntimacyRecordTransaction#doRecord}（@Transactional(REQUIRES_NEW)）</li>
 * </ul>
 *
 * <p>OptimisticLockingFailureException 时每次 retry 开一个全新事务（旧事务已 rollback 释放锁），
 * 下次读到最新 {@code version}。3 次仍失败抛 BusinessException(INTIMACY_CONCURRENT_UPDATE)。
 *
 * <p>如果把 @Transactional 直接加在本类的 recordEvent 上：retry 全部在同一被标记为 rollback-only
 * 的事务里跑，永远不会成功——这是 D5 code-review 发现的严重 bug，本拆分是修复。
 */
@Service
@RequiredArgsConstructor
public class IntimacyRecordService {

    private static final int MAX_RETRY = 3;

    private final IntimacyRecordTransaction tx;

    public void recordEvent(IntimacyEvent event) {
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            try {
                tx.doRecord(event);
                return;
            } catch (OptimisticLockingFailureException ignored) {
                // 下次 retry 开新事务，重新读最新 version
            }
        }
        throw new BusinessException(CharacterErrCode.INTIMACY_CONCURRENT_UPDATE);
    }
}
