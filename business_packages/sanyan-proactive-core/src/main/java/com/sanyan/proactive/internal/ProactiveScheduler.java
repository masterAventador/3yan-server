package com.sanyan.proactive.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.List;

/**
 * 调度主循环（spec §5.1 ②）：每 30s 扫到期 SCHEDULED 事件，逐条标 PROCESSING → 委托
 * {@link ProactiveDispatcher} 分发 → 保存最终状态。失败 failCount++ 退避重排，超
 * {@link #MAX_FAIL} 次标 FAILED（spec §5.1 失败段）。
 *
 * <p>整个 {@link #poll} 包 try-catch 兜底（照 {@code RagIndexWorker}）：Redis/DB 异常
 * 安静跳过，下次 fixedDelay 触发重试，避免每 30s 一条 ERROR stack trace 噪音。
 *
 * <p>{@code @EnableConfigurationProperties} 在此激活 {@link ProactiveProperties}（供 FrequencyGate 注入）。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(ProactiveProperties.class)
@RequiredArgsConstructor
public class ProactiveScheduler {

    /** 单轮最多领取条数（SKIP LOCKED 防并发重复）。 */
    static final int BATCH_LIMIT = 50;
    /** 失败重试上限：超过则标 FAILED。 */
    static final int MAX_FAIL = 3;

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
                processOne(event);
            }
        } catch (Exception e) {
            log.debug("ProactiveScheduler.poll skipped (Redis/DB 不可用?): {}", e.getMessage());
        }
    }

    private void processOne(EventPendingEntity event) {
        // 领取即标 PROCESSING，防止本轮 / 其他实例重复处理
        event.setStatus(EventStatus.PROCESSING);
        repo.save(event);
        try {
            dispatcher.dispatch(event);          // 内部设置 SENT / CANCELLED
            repo.save(event);
        } catch (Exception err) {
            handleFailure(event, err);
        }
    }

    private void handleFailure(EventPendingEntity event, Exception err) {
        int newFailCount = event.getFailCount() + 1;
        event.setFailCount(newFailCount);
        event.setLastError(err.getMessage());
        if (newFailCount > MAX_FAIL) {
            event.setStatus(EventStatus.FAILED);
            log.error("主动消息分发超 {} 次，标 FAILED: eventId={}, userId={}, err={}",
                    MAX_FAIL, event.getId(), event.getUserId(), err.getMessage());
        } else {
            // 退避重排：线性退避，failCount 越大排越靠后
            event.setStatus(EventStatus.SCHEDULED);
            event.setScheduledAt(Instant.now().plusSeconds(60L * newFailCount));
            log.warn("主动消息分发失败第 {} 次，退避重排: eventId={}, userId={}, err={}",
                    newFailCount, event.getId(), event.getUserId(), err.getMessage());
        }
        repo.save(event);
    }
}
