package com.sanyan.proactive.trigger;

import com.sanyan.memory.event.MemoryItemScheduledEvent;
import com.sanyan.proactive.internal.EventPendingEntity;
import com.sanyan.proactive.internal.EventPendingRepository;
import com.sanyan.proactive.internal.EventType;
import com.sanyan.proactive.internal.PayloadSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * c 事件追问 / d 情绪关怀触发器（订阅 memory 域事件）。
 *
 * <p>订阅 {@link MemoryItemScheduledEvent}（@Async + AFTER_COMMIT），按 kind 排对应 event：
 * <ul>
 *   <li>PLAN_EVENT → {@link EventType#C_EVENT_FOLLOWUP}</li>
 *   <li>EMOTION    → {@link EventType#D_EMOTION_CARE}</li>
 *   <li>PROMISE    → 不排（spec §12 本期仅留存不主动跟进）</li>
 * </ul>
 * scheduledAt = event.salientAt，payload 记 memoryItemId（供生成器取 content）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MemoryItemScheduledListener {

    static final String KIND_PLAN_EVENT = "PLAN_EVENT";
    static final String KIND_EMOTION = "EMOTION";

    private final EventPendingRepository eventRepo;

    // fallbackExecution=true：发布方 MemoryItemExtractService.extract() 故意不加 @Transactional
    // （避免 LLM 调用全程占用 DB 连接），每个 repository.save 走独立小事务、提交后随即 publishEvent，
    // 此时调用线程上无活跃事务。默认 @TransactionalEventListener(AFTER_COMMIT) 在无事务时会被
    // Spring 静默丢弃 → events_pending 永远不写 → 主动询问永远不排期。memory_item 在发布前已独立
    // 提交，不存在"事务回滚导致事件失真"的风险，启用 fallback 让 listener 在无事务时也立即触发；
    // 未来若发布方包了事务，AFTER_COMMIT 行为不变。
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMemoryItemScheduled(MemoryItemScheduledEvent event) {
        try {
            EventType type;
            if (KIND_PLAN_EVENT.equals(event.kind())) {
                type = EventType.C_EVENT_FOLLOWUP;
            } else if (KIND_EMOTION.equals(event.kind())) {
                type = EventType.D_EMOTION_CARE;
            } else {
                // PROMISE / 未知 kind 不排（spec §12 本期仅留存）
                log.debug("MemoryItemScheduledListener: 忽略 kind={} memoryItemId={}",
                        event.kind(), event.memoryItemId());
                return;
            }

            EventPendingEntity e = new EventPendingEntity();
            e.setUserId(event.userId());
            e.setCharacterId(event.characterId());
            e.setEventType(type);
            e.setScheduledAt(event.salientAt());
            e.setPayload(PayloadSupport.toJson(Map.of(PayloadSupport.MEMORY_ITEM_ID, event.memoryItemId())));
            eventRepo.save(e);

            log.info("MemoryItemScheduledListener: 已排 {} event memoryItemId={} scheduledAt={}",
                    type, event.memoryItemId(), event.salientAt());
        } catch (Exception ex) {
            // @Async 后台线程；save 失败静默降级，不影响主链路（与 L1/L2 触发器风格一致）
            log.warn("MemoryItemScheduledListener: 排期失败 memoryItemId={} kind={}: {}",
                    event.memoryItemId(), event.kind(), ex.getMessage());
        }
    }
}
