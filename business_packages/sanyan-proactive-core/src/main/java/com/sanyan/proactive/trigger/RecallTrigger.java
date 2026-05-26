package com.sanyan.proactive.trigger;

import com.sanyan.character.CharacterApi;
import com.sanyan.character.dto.RelationshipDto;
import com.sanyan.common.cache.KvCache;
import com.sanyan.common.ws.LastActiveTracker;
import com.sanyan.proactive.internal.EventPendingEntity;
import com.sanyan.proactive.internal.EventPendingRepository;
import com.sanyan.proactive.internal.EventType;
import com.sanyan.proactive.internal.PayloadSupport;
import com.sanyan.proactive.internal.ProactiveProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 失联召回触发器（{@link EventType#B_RECALL}）。
 *
 * <p>每 15min 扫一遍有关系的 user：按 last_active 离线时长命中 thresholdsHours 哪一档
 * （24/72/168h → escalationLevel 0/1/2，取命中的最高档），用
 * {@code proactive:recall:{userId}:{level}} 去重（每档每用户只召回一次，TTL 8d），
 * 成功才排 B_RECALL。用户回访刷新 last_active 后阶梯自然重置（去重 key 过期 / 离线时长归零）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecallTrigger {

    static final Duration DEDUP_TTL = Duration.ofDays(8);
    static final String DEDUP_KEY_PREFIX = "proactive:recall:";

    private final CharacterApi characterApi;
    private final EventPendingRepository eventRepo;
    private final LastActiveTracker lastActiveTracker;
    private final KvCache kvCache;
    private final ProactiveProperties props;

    @Scheduled(fixedDelay = 15 * 60 * 1000)   // 15min
    public void scanAndEnqueue() {
        List<Long> userIds;
        try {
            userIds = characterApi.listActiveRelationshipUserIds(PayloadSupport.CHARACTER_ID);
        } catch (Exception e) {
            log.warn("失联召回：拉取活跃用户失败，跳过本轮: {}", e.getMessage());
            return;
        }
        for (Long userId : userIds) {
            try {
                enqueueOne(userId);
            } catch (Exception e) {
                log.warn("失联召回：user {} 处理失败，跳过: {}", userId, e.getMessage());
            }
        }
    }

    private void enqueueOne(Long userId) {
        Optional<Instant> lastActive = lastActiveTracker.lastActiveAt(userId);
        if (lastActive.isEmpty()) {
            return;   // 从无活跃记录，不召回
        }
        long offlineHours = Duration.between(lastActive.get(), Instant.now()).toHours();
        int level = resolveLevel(offlineHours);
        if (level < 0) {
            return;   // 未到第一档
        }

        // stage 场景开关
        RelationshipDto rel = characterApi.findOrCreateRelationship(userId, PayloadSupport.CHARACTER_ID);
        ProactiveProperties.SceneFlags scenes = props.getScenesByStage().get(rel.currentStage());
        if (scenes == null || !scenes.isRecall()) {
            return;
        }

        // 每档每用户去重
        String dedupKey = DEDUP_KEY_PREFIX + userId + ":" + level;
        if (!kvCache.setIfAbsent(dedupKey, "1", DEDUP_TTL)) {
            return;
        }

        EventPendingEntity e = new EventPendingEntity();
        e.setUserId(userId);
        e.setCharacterId(PayloadSupport.CHARACTER_ID);
        e.setEventType(EventType.B_RECALL);
        // scheduledAt = now：召回事件立即可被调度，无需像早晚安那样分散（失联召回要尽快触达）
        e.setScheduledAt(Instant.now());
        e.setPayload(PayloadSupport.toJson(Map.of(PayloadSupport.ESCALATION_LEVEL, level)));
        eventRepo.save(e);
    }

    /** 返回命中的最高档 level（0/1/2）；未到第一档返回 -1。 */
    private int resolveLevel(long offlineHours) {
        List<Integer> thresholds = props.getRecall().getThresholdsHours();
        int level = -1;
        for (int i = 0; i < thresholds.size(); i++) {
            if (offlineHours >= thresholds.get(i)) {
                level = i;
            }
        }
        return level;
    }
}
