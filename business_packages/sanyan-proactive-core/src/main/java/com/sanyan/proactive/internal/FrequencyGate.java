package com.sanyan.proactive.internal;

import com.sanyan.chat.ChatApi;
import com.sanyan.common.cache.KvCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 主动消息发送前频率门控（spec §6）：免打扰 + stage 场景开关 + 每日上限，三关全过才放行。
 * stage 取自 character-api findOrCreateRelationship（不涨分），由调用方 Dispatcher 传入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FrequencyGate {

    /** 当日已发计数 key（附录 G）：proactive:sent:{userId}:{yyyy-MM-dd}。 */
    private static final String SENT_KEY_PREFIX = "proactive:sent:";
    private static final Duration SENT_TTL = Duration.ofHours(36);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final KvCache kvCache;
    private final ProactiveProperties props;
    private final ChatApi chatApi;

    /** 可测时钟；默认系统时区。测试经 ReflectionTestUtils 注入固定 Clock。 */
    private Clock clock = Clock.systemDefaultZone();

    public boolean allow(Long userId, Long characterId, EventType type, int currentStage) {
        if (type == EventType.A_GREETING && backoffActive(userId)) {
            log.info("门控拒绝（互动退避：连续未回应，暂停早晚安）: userId={}", userId);
            return false;
        }
        if (isQuietHours()) {
            log.debug("门控拒绝（免打扰时段）: userId={}, type={}", userId, type);
            return false;
        }
        if (!sceneEnabled(type, currentStage)) {
            log.debug("门控拒绝（stage {} 场景 {} 未开启）: userId={}", currentStage, type, userId);
            return false;
        }
        if (dailyCountReached(userId, currentStage)) {
            log.debug("门控拒绝（当日已达上限）: userId={}, stage={}", userId, currentStage);
            return false;
        }
        return true;
    }

    /**
     * 互动退避：自用户最后回话以来未回应的主动消息达到阈值时，掐早晚安（仅 A_GREETING 关心）。
     * 阈值配 0 关闭退避；查询失败按降级处理，fail-open 放行（退避只是降频优化，不应阻断正常门控）。
     */
    private boolean backoffActive(Long userId) {
        int threshold = props.getUnansweredGreetingBackoffThreshold();
        if (threshold <= 0) {
            return false;   // 配 0 关闭退避
        }
        try {
            return chatApi.countUnansweredProactive(userId) >= threshold;
        } catch (Exception e) {
            log.warn("互动退避查询失败，跳过退避关: userId={}, err={}", userId, e.getMessage());
            return false;
        }
    }

    public void recordSent(Long userId) {
        kvCache.increment(sentKey(userId), SENT_TTL);
    }

    private boolean isQuietHours() {
        int hour = LocalTime.now(clock).getHour();
        int start = props.getQuietHours().getStart();
        int end = props.getQuietHours().getEnd();
        if (start <= end) {
            // 不跨午夜
            return hour >= start && hour < end;
        }
        // 跨午夜（如 23..8）
        return hour >= start || hour < end;
    }

    private boolean sceneEnabled(EventType type, int stage) {
        ProactiveProperties.SceneFlags flags = props.getScenesByStage().get(stage);
        if (flags == null) {
            return false;
        }
        return switch (type) {
            // 早晚安合并为 A_GREETING：morning 或 night 任一开即放行，具体发早/晚由触发 cron 决定
            case A_GREETING -> flags.isMorning() || flags.isNight();
            case B_RECALL -> flags.isRecall();
            case C_EVENT_FOLLOWUP -> flags.isEventFollowup();
            case D_EMOTION_CARE -> flags.isEmotionCare();
        };
    }

    private boolean dailyCountReached(Long userId, int stage) {
        List<Integer> caps = props.getDailyCapByStage();
        if (stage < 0 || stage >= caps.size()) {
            return true; // stage 越界视为已满（保守拒绝）
        }
        int cap = caps.get(stage);
        int sent = parseCount(kvCache.get(sentKey(userId)));
        return sent >= cap;
    }

    private static int parseCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String sentKey(Long userId) {
        return SENT_KEY_PREFIX + userId + ":" + LocalDate.now(clock).format(DATE_FMT);
    }
}
