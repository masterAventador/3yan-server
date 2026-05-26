package com.sanyan.proactive.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 调度主循环（spec §5.1 ②）：每 30s 触发 {@link ProactiveClaimTransaction#claimAndDispatch}
 * 领取到期 SCHEDULED 事件、标 PROCESSING 并 fire-and-forget 委托 {@link ProactiveDispatcher}
 * （@Async）异步处理完整生命周期。
 *
 * <p><b>主循环不阻塞（H2 约束）：</b>dispatch 标 {@code @Async}，跨 Bean 调用立即返回，本
 * {@code @Scheduled(30s)} 单线程主循环不被投递的同步等 ACK 阻塞，避免调度饥饿。生成/投递/
 * 标终态（SENT/CANCELLED/FAILED）/失败退避全部内聚在 dispatch 内（含 save）。
 *
 * <p><b>事务边界拆到 {@link ProactiveClaimTransaction}：</b>领取 + 标 PROCESSING 需在同一事务内
 * 让 SKIP LOCKED 行锁持有到提交（防双重投递），但 {@code @Transactional} 不能与本类的 try-catch
 * 兜底同方法——否则代理 commit 阶段的 {@code UnexpectedRollbackException} 会逃过 try-catch。
 * 故事务逻辑拆到独立 Bean，poll 在事务外包 try-catch（详见 {@link ProactiveClaimTransaction}）。
 *
 * <p>整个 {@link #poll} 包 try-catch 兜底（照 {@code RagIndexWorker}）：Redis/DB 异常
 * log.warn 跳过，下次 fixedDelay 触发重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProactiveScheduler {

    private final ProactiveClaimTransaction claimTransaction;

    @Scheduled(fixedDelay = 30000)
    public void poll() {
        try {
            claimTransaction.claimAndDispatch();
        } catch (Exception e) {
            log.warn("ProactiveScheduler.poll skipped (Redis/DB 不可用?): {}", e.getMessage());
        }
    }
}
