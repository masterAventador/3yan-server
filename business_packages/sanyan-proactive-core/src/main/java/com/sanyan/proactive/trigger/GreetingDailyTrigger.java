package com.sanyan.proactive.trigger;

import com.sanyan.character.CharacterApi;
import com.sanyan.character.dto.RelationshipDto;
import com.sanyan.proactive.internal.EventPendingEntity;
import com.sanyan.proactive.internal.EventPendingRepository;
import com.sanyan.proactive.internal.EventType;
import com.sanyan.proactive.internal.PayloadSupport;
import com.sanyan.proactive.internal.ProactiveProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 早晚安触发器（{@link EventType#A_GREETING}）。
 *
 * <p>两个 cron 方法分别在 morningCron / nightCron 命中时遍历有关系的 user，
 * 按当前 stage 的 scenesByStage[stage].morning/night 开关决定是否排期。
 * 每个 user 的 scheduledAt = now + random(0..scatterWindowMinutes)，分散并发负载（spec §5.3）。
 *
 * <p>@Scheduled 安静降级风格参照 memory-core RagIndexWorker：单 user 处理异常不阻断其余。
 * 本期单角色 characterId 固定 {@link PayloadSupport#CHARACTER_ID}。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GreetingDailyTrigger {

    static final String TIME_MORNING = "morning";
    static final String TIME_NIGHT = "night";

    private final CharacterApi characterApi;
    private final EventPendingRepository eventRepo;
    private final ProactiveProperties props;

    @Scheduled(cron = "${sanyan.proactive.greeting.morning-cron:0 0 8 * * *}")
    public void scheduleMorning() {
        enqueueAll(TIME_MORNING);
    }

    @Scheduled(cron = "${sanyan.proactive.greeting.night-cron:0 30 22 * * *}")
    public void scheduleNight() {
        enqueueAll(TIME_NIGHT);
    }

    void enqueueAll(String timeOfDay) {
        List<Long> userIds;
        try {
            userIds = characterApi.listActiveRelationshipUserIds(PayloadSupport.CHARACTER_ID);
        } catch (Exception e) {
            log.warn("早晚安触发：拉取活跃用户失败，跳过本轮: {}", e.getMessage());
            return;
        }
        for (Long userId : userIds) {
            try {
                enqueueOne(userId, timeOfDay);
            } catch (Exception e) {
                log.warn("早晚安触发：user {} 排期失败，跳过: {}", userId, e.getMessage());
            }
        }
    }

    private void enqueueOne(Long userId, String timeOfDay) {
        RelationshipDto rel = characterApi.findOrCreateRelationship(userId, PayloadSupport.CHARACTER_ID);
        ProactiveProperties.SceneFlags scenes = props.getScenesByStage().get(rel.currentStage());

        // null guard：未配置的 stage 视为全部场景关闭（spec §6.3 注释）
        if (scenes == null) {
            return;
        }

        boolean enabled = TIME_MORNING.equals(timeOfDay) ? scenes.isMorning() : scenes.isNight();
        if (!enabled) {
            return;
        }

        int window = props.getScatterWindowMinutes();
        if (window <= 0) {
            log.warn("scatterWindowMinutes 配置异常: {}, 回退为不分散", window);
        }
        int scatterMin = ThreadLocalRandom.current().nextInt(Math.max(1, window));
        Instant scheduledAt = Instant.now().plus(scatterMin, ChronoUnit.MINUTES);

        EventPendingEntity e = new EventPendingEntity();
        e.setUserId(userId);
        e.setCharacterId(PayloadSupport.CHARACTER_ID);
        e.setEventType(EventType.A_GREETING);
        e.setScheduledAt(scheduledAt);
        e.setPayload(PayloadSupport.toJson(Map.of(PayloadSupport.TIME_OF_DAY, timeOfDay)));
        eventRepo.save(e);
    }
}
