package com.sanyan.proactive.internal;

import com.sanyan.chat.ChatApi;
import com.sanyan.common.cache.KvCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FrequencyGateTest {

    @Mock KvCache kvCache;
    @Mock ChatApi chatApi;

    ProactiveProperties props;
    FrequencyGate gate;

    /** 固定一个「白天 10:00」的 Clock，避开免打扰；UTC 简化。 */
    private static Clock dayClock() {
        return Clock.fixed(Instant.parse("2026-05-27T10:00:00Z"), ZoneId.of("UTC"));
    }

    /** 固定一个「凌晨 2:00」的 Clock，落在免打扰 [23,8) 内。 */
    private static Clock nightClock() {
        return Clock.fixed(Instant.parse("2026-05-27T02:00:00Z"), ZoneId.of("UTC"));
    }

    @BeforeEach
    void setUp() {
        props = new ProactiveProperties();
        props.setDailyCapByStage(List.of(2, 3, 4, 5, 6));
        Map<Integer, ProactiveProperties.SceneFlags> scenes = new HashMap<>();
        ProactiveProperties.SceneFlags s2 = new ProactiveProperties.SceneFlags();
        s2.setMorning(true); s2.setNight(true); s2.setRecall(true);
        s2.setEventFollowup(true); s2.setEmotionCare(true);
        scenes.put(2, s2);
        ProactiveProperties.SceneFlags s0 = new ProactiveProperties.SceneFlags();
        s0.setRecall(true); s0.setEventFollowup(true); // 陌生人不开早晚安/情绪
        scenes.put(0, s0);
        props.setScenesByStage(scenes);

        gate = new FrequencyGate(kvCache, props, chatApi);
        ReflectionTestUtils.setField(gate, "clock", dayClock());
    }

    @Test
    void allow_should_pass_when_under_cap_scene_on_and_not_quiet() {
        when(kvCache.get(anyString())).thenReturn("1"); // stage2 cap=4，已发 1 条

        boolean allowed = gate.allow(1L, 99L, EventType.A_GREETING, 2);

        assertThat(allowed).isTrue();
    }

    @Test
    void allow_should_reject_when_daily_cap_reached() {
        when(kvCache.get(anyString())).thenReturn("4"); // stage2 cap=4，已满

        assertThat(gate.allow(1L, 99L, EventType.A_GREETING, 2)).isFalse();
    }

    @Test
    void allow_should_reject_when_scene_flag_off_for_stage() {
        // stage0 emotionCare=false → D_EMOTION_CARE 被场景开关拒（早于每日计数检查，不触发 kvCache.get）
        assertThat(gate.allow(1L, 99L, EventType.D_EMOTION_CARE, 0)).isFalse();
    }

    @Test
    void allow_should_reject_during_quiet_hours() {
        ReflectionTestUtils.setField(gate, "clock", nightClock());
        // 凌晨 2:00 落在 [23,8) 免打扰，直接拒（不论计数/场景）
        assertThat(gate.allow(1L, 99L, EventType.A_GREETING, 2)).isFalse();
    }

    @Test
    void allow_should_reject_when_stage_has_no_scene_config() {
        // stage 9 无 scenesByStage 配置，sceneEnabled 返回 false（早于每日计数检查，不触发 kvCache.get）
        assertThat(gate.allow(1L, 99L, EventType.A_GREETING, 9)).isFalse();
    }

    @Test
    void recordSent_should_increment_daily_counter_with_36h_ttl() {
        gate.recordSent(1L);
        verify(kvCache).increment(anyString(), eq(Duration.ofHours(36)));
    }

    @Test
    void allow_should_block_greeting_when_unanswered_exceeds_threshold() {
        // 阈值默认 3；造 4 条未回应。前三关（非免打扰 + stage2 场景开 + 未达上限）均放行，唯独退避拦截。
        when(chatApi.countUnansweredProactive(1L)).thenReturn(4L);

        assertThat(gate.allow(1L, 99L, EventType.A_GREETING, 2)).isFalse();
    }

    @Test
    void allow_should_still_permit_recall_when_unanswered_exceeds_threshold() {
        // 召回不受退避影响：B_RECALL 根本不查 chatApi，连续未回应也照常放行
        when(kvCache.get(anyString())).thenReturn("1"); // 未达每日上限

        assertThat(gate.allow(1L, 99L, EventType.B_RECALL, 2)).isTrue();
    }

    @Test
    void allow_should_permit_greeting_when_unanswered_below_threshold() {
        when(chatApi.countUnansweredProactive(1L)).thenReturn(2L);
        when(kvCache.get(anyString())).thenReturn("1"); // 未达每日上限

        assertThat(gate.allow(1L, 99L, EventType.A_GREETING, 2)).isTrue();
    }

    @Test
    void allow_should_permit_greeting_when_backoff_query_fails() {
        // 退避查询抛异常 → fail-open 放行（best-effort 降频优化，不应阻断正常门控）。
        // 钉住降级方向：若有人把 catch 改成异常时拦截，chat DB 抖动会误杀所有早晚安。
        when(chatApi.countUnansweredProactive(1L)).thenThrow(new RuntimeException("chat db down"));
        when(kvCache.get(anyString())).thenReturn("1"); // 未达每日上限

        assertThat(gate.allow(1L, 99L, EventType.A_GREETING, 2)).isTrue();
    }
}
