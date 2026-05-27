package com.sanyan.proactive.api;

import com.sanyan.common.error.BusinessException;
import com.sanyan.proactive.ProactiveApi;
import com.sanyan.proactive.dto.ProactiveTriggerResult;
import com.sanyan.proactive.internal.EventPendingEntity;
import com.sanyan.proactive.internal.EventPendingRepository;
import com.sanyan.proactive.internal.EventStatus;
import com.sanyan.proactive.internal.EventType;
import com.sanyan.proactive.internal.ProactiveErrCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * {@link ProactiveApi} 实现：手动触发一次某用户某场景的调度（测试 / 运维用）。
 * 仅负责「插一条 scheduled 事件」，后续门控 / 生成 / 投递由 ProactiveScheduler → Dispatcher 处理。
 */
@Service
@RequiredArgsConstructor
public class ProactiveApiImpl implements ProactiveApi {

    private final EventPendingRepository repo;

    @Override
    @Transactional
    public ProactiveTriggerResult triggerNow(Long userId, Long characterId, String eventType) {
        EventType type = parseEventType(eventType);
        EventPendingEntity event = new EventPendingEntity();
        event.setUserId(userId);
        event.setCharacterId(characterId);
        event.setEventType(type);
        event.setStatus(EventStatus.SCHEDULED);
        event.setScheduledAt(Instant.now());
        repo.save(event);
        return new ProactiveTriggerResult(true, "已排入调度队列");
    }

    private static EventType parseEventType(String raw) {
        try {
            return EventType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ProactiveErrCode.PROACTIVE_EVENT_TYPE_INVALID,
                    "未知的主动事件类型: " + raw);
        }
    }
}
