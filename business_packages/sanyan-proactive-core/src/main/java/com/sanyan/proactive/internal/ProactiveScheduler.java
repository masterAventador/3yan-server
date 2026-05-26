package com.sanyan.proactive.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 调度主循环（spec §5.1 ②）：每 30s 扫到期 SCHEDULED 事件，逐条标 PROCESSING（save）后
 * fire-and-forget 委托 {@link ProactiveDispatcher#dispatch}（@Async）异步处理完整生命周期。
 *
 * <p><b>主循环不阻塞（H2 约束）：</b>dispatch 标 {@code @Async}，跨 Bean 调用立即返回，本
 * {@code @Scheduled(30s)} 单线程主循环不被投递的同步等 ACK 阻塞，避免调度饥饿。生成/投递/
 * 标终态（SENT/CANCELLED/FAILED）/失败退避全部内聚在 dispatch 内（含 save），主循环不再做。
 *
 * <p>整个 {@link #poll} 包 try-catch 兜底（照 {@code RagIndexWorker}）：Redis/DB 异常
 * 安静跳过，下次 fixedDelay 触发重试，避免每 30s 一条 ERROR stack trace 噪音。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProactiveScheduler {

    /** 单轮最多领取条数（SKIP LOCKED 防并发重复）。 */
    static final int BATCH_LIMIT = 50;

    private final EventPendingRepository repo;
    private final ProactiveDispatcher dispatcher;

    @Scheduled(fixedDelay = 30000)
    public void poll() {
        try {
            List<EventPendingEntity> due = repo.findDueForUpdate(Instant.now(), BATCH_LIMIT);
            if (due == null || due.isEmpty()) {
                return;
            }
            for (EventPendingEntity event : due) {
                // 领取即标 PROCESSING + save，防止本轮 / 其他实例重复处理；
                // 随即 fire-and-forget 异步分发（@Async 立即返回，不等投递、不做终态 save）。
                event.setStatus(EventStatus.PROCESSING);
                repo.save(event);
                dispatcher.dispatch(event);
            }
        } catch (Exception e) {
            log.debug("ProactiveScheduler.poll skipped (Redis/DB 不可用?): {}", e.getMessage());
        }
    }
}
