package com.sanyan.proactive.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 主动消息"领取"事务内层（spec §5.1 ②）。
 *
 * <p><b>独立 Bean 持有 {@link #claimAndDispatch} 是为让 Spring AOP 事务代理生效</b>（同
 * {@code IntimacyRecordTransaction} 模式）：{@link ProactiveScheduler#poll} 在事务**外**做
 * try-catch 兜底，调用本类经代理开启事务。
 *
 * <p><b>{@code @Transactional} 防双重投递：</b>{@code findDueForUpdate} 的 {@code FOR UPDATE
 * SKIP LOCKED} 行锁随其所在事务提交而释放。若领取与 {@code save(PROCESSING)} 分属各自独立事务，
 * 两者之间存在竞态窗口——多实例下另一实例可在此窗口领取同一行 → 双重投递。本方法用一个事务把
 * SKIP LOCKED 领取 + 循环内全部 setStatus(PROCESSING)+save 包住，锁持有到提交，杜绝窗口。
 *
 * <p><b>不能内联回 poll：</b>若把 {@code @Transactional} 加到 poll 上（与 try-catch 同方法），
 * 领取失败时事务被标 rollback-only，内层 try-catch 吞掉异常后，代理在 <b>commit</b> 阶段抛
 * {@code UnexpectedRollbackException}——该异常发生在 poll 方法体之外（代理层），逃过 try-catch
 * 直冒调度器，每 30s 一条 ERROR。故事务边界必须是被 poll 包裹的内层方法。
 *
 * <p>poll 是短事务（只领取+标 PROCESSING，快提交）；{@link ProactiveDispatcher#dispatch}
 * （@Async）在事务内 submit 立即返回，本方法不等 worker，无死锁。
 */
@Service
@RequiredArgsConstructor
public class ProactiveClaimTransaction {

    /** 单轮最多领取条数（SKIP LOCKED 防并发重复）。 */
    static final int BATCH_LIMIT = 50;

    private final EventPendingRepository repo;
    private final ProactiveDispatcher dispatcher;

    @Transactional
    public void claimAndDispatch() {
        List<EventPendingEntity> due = repo.findDueForUpdate(Instant.now(), BATCH_LIMIT);
        if (due == null || due.isEmpty()) {
            return;
        }
        for (EventPendingEntity event : due) {
            // 领取即标 PROCESSING + save（锁持有到本事务提交），防止本轮 / 其他实例重复处理；
            // 随即 fire-and-forget 异步分发（@Async 立即返回，不等投递、不做终态 save）。
            event.setStatus(EventStatus.PROCESSING);
            repo.save(event);
            dispatcher.dispatch(event);
        }
    }
}
