package com.sanyan.memory.internal.profile;

import com.sanyan.common.error.BusinessException;
import com.sanyan.memory.MemoryConstants;
import com.sanyan.memory.internal.MemoryErrCode;
import com.sanyan.memory.internal.profile.dto.ProfileExtraction;
import com.sanyan.memory.internal.profile.fixtures.MemoryProfileTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan 2 Task O3：{@link MemoryProfileMergeService} Mockito 单测。
 *
 * <p>覆盖合并规则（来自 plan §O3）：
 * <ul>
 *   <li>{@code basic_info.name}：已存在则不覆盖（即使再传新 name 也保留旧值）</li>
 *   <li>{@code basic_info.occupation/age/hometown}：每次抽取允许覆盖</li>
 *   <li>{@code preferences.foods}：append + 大小写规范化后去重</li>
 *   <li>{@code events}：追加 + 自动带 {@code extracted_at} 时间戳</li>
 *   <li>{@code emotion_line}：超过 {@link MemoryConstants#PROFILE_EMOTION_LINE_MAX}（=50）条时丢最早</li>
 * </ul>
 *
 * <p>以及乐观锁链路：
 * <ul>
 *   <li>首次冲突重试一次后成功</li>
 *   <li>重试后再次冲突 → 抛 {@link BusinessException}({@link MemoryErrCode#PROFILE_MERGE_CONFLICT})</li>
 * </ul>
 *
 * <p>用 fixture {@link MemoryProfileTestFixtures} 构造 Entity，遵守
 * java-backend-business-layer.md §5.2 Object Mother 强制。
 */
@ExtendWith(MockitoExtension.class)
class MemoryProfileMergeServiceTest {

    @Mock
    private MemoryProfileRepository repository;

    private MemoryProfileMergeService service;

    private MemoryProfileMergeService newService() {
        return new MemoryProfileMergeService(repository);
    }

    // ===== basic_info merge rules =====

    @Test
    void merge_shouldNotOverwriteExistingName() {
        service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.profileWithName("小明");
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(MemoryProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> basicUpdates = new HashMap<>();
        basicUpdates.put("name", "小红");
        ProfileExtraction extraction = new ProfileExtraction(basicUpdates, null, null, null);

        MemoryProfileEntity result = service.merge(1L, 1L, extraction);

        @SuppressWarnings("unchecked")
        Map<String, Object> basicInfo = (Map<String, Object>) result.getProfileJsonb().get("basic_info");
        assertThat(basicInfo.get("name"))
                .as("name 已存在时不允许覆盖（保留首次抽取的值）")
                .isEqualTo("小明");
    }

    @Test
    void merge_shouldWriteNameWhenInitiallyNull() {
        service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.validProfile();
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(MemoryProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> basicUpdates = new HashMap<>();
        basicUpdates.put("name", "小明");
        ProfileExtraction extraction = new ProfileExtraction(basicUpdates, null, null, null);

        MemoryProfileEntity result = service.merge(1L, 1L, extraction);

        @SuppressWarnings("unchecked")
        Map<String, Object> basicInfo = (Map<String, Object>) result.getProfileJsonb().get("basic_info");
        assertThat(basicInfo.get("name"))
                .as("name 当前为 null 时首次写入应生效")
                .isEqualTo("小明");
    }

    @Test
    void merge_shouldOverwriteOccupation() {
        service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.validProfile();
        @SuppressWarnings("unchecked")
        Map<String, Object> basicInfo = (Map<String, Object>) existing.getProfileJsonb().get("basic_info");
        basicInfo.put("occupation", "后端工程师");
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(MemoryProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> basicUpdates = new HashMap<>();
        basicUpdates.put("occupation", "产品经理");
        ProfileExtraction extraction = new ProfileExtraction(basicUpdates, null, null, null);

        MemoryProfileEntity result = service.merge(1L, 1L, extraction);

        @SuppressWarnings("unchecked")
        Map<String, Object> mergedBasicInfo = (Map<String, Object>) result.getProfileJsonb().get("basic_info");
        assertThat(mergedBasicInfo.get("occupation"))
                .as("occupation 允许覆盖（用户可能换工作）")
                .isEqualTo("产品经理");
    }

    @Test
    void merge_shouldOverwriteAgeAndHometown() {
        service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.validProfile();
        @SuppressWarnings("unchecked")
        Map<String, Object> basicInfo = (Map<String, Object>) existing.getProfileJsonb().get("basic_info");
        basicInfo.put("age", 25);
        basicInfo.put("hometown", "杭州");
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(MemoryProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> basicUpdates = new HashMap<>();
        basicUpdates.put("age", 26);
        basicUpdates.put("hometown", "上海");
        ProfileExtraction extraction = new ProfileExtraction(basicUpdates, null, null, null);

        MemoryProfileEntity result = service.merge(1L, 1L, extraction);

        @SuppressWarnings("unchecked")
        Map<String, Object> mergedBasicInfo = (Map<String, Object>) result.getProfileJsonb().get("basic_info");
        assertThat(mergedBasicInfo.get("age"))
                .as("age 允许覆盖（用户会长大）")
                .isEqualTo(26);
        assertThat(mergedBasicInfo.get("hometown"))
                .as("hometown 允许覆盖（用户可能搬家）")
                .isEqualTo("上海");
    }

    // ===== preferences merge rules =====

    @Test
    void merge_shouldAppendAndDedupePreferencesFoodsCaseInsensitively() {
        service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.profileWithFoods(
                new ArrayList<>(List.of("寿司", "Pizza")));
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(MemoryProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, List<String>> prefsAppended = new HashMap<>();
        // "pizza" 应被去重（与已有 "Pizza" 小写规范化后相同）；"火锅"是新项
        prefsAppended.put("foods", List.of("pizza", "火锅"));
        ProfileExtraction extraction = new ProfileExtraction(null, prefsAppended, null, null);

        MemoryProfileEntity result = service.merge(1L, 1L, extraction);

        @SuppressWarnings("unchecked")
        Map<String, List<String>> prefs = (Map<String, List<String>>) result.getProfileJsonb().get("preferences");
        assertThat(prefs.get("foods"))
                .as("foods 应该 append + 大小写规范化去重")
                .containsExactly("寿司", "Pizza", "火锅");
    }

    // ===== events merge rules =====

    @Test
    void merge_shouldAppendEventsWithExtractedAtTimestamp() {
        service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.validProfile();
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(MemoryProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> newEvent = new LinkedHashMap<>();
        newEvent.put("type", "interview");
        newEvent.put("content", "周三技术面试");
        ProfileExtraction extraction = new ProfileExtraction(
                null, null, List.of(newEvent), null);

        MemoryProfileEntity result = service.merge(1L, 1L, extraction);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) result.getProfileJsonb().get("events");
        assertThat(events).hasSize(1);
        Map<String, Object> persisted = events.get(0);
        assertThat(persisted)
                .as("event 必须保留原字段")
                .containsEntry("type", "interview")
                .containsEntry("content", "周三技术面试");
        assertThat(persisted)
                .as("event 必须自动补上 extracted_at 时间戳")
                .containsKey("extracted_at");
        assertThat(persisted.get("extracted_at"))
                .asString()
                .as("extracted_at 应该是 ISO-8601 Instant 格式")
                .matches("^\\d{4}-\\d{2}-\\d{2}T.*Z$");
    }

    // ===== emotion_line merge rules =====

    @Test
    void merge_shouldAppendEmotionSignalAndKeepOnlyLatest50() {
        service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.validProfile();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> existingLine =
                (List<Map<String, Object>>) existing.getProfileJsonb().get("emotion_line");
        // 已经有 50 条
        for (int i = 0; i < MemoryConstants.PROFILE_EMOTION_LINE_MAX; i++) {
            Map<String, Object> e = new HashMap<>();
            e.put("signal", "情绪#" + i);
            e.put("at", "2025-01-01T00:00:0" + (i % 10) + "Z");
            existingLine.add(e);
        }
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(MemoryProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ProfileExtraction extraction = new ProfileExtraction(null, null, null, "焦虑");

        MemoryProfileEntity result = service.merge(1L, 1L, extraction);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mergedLine =
                (List<Map<String, Object>>) result.getProfileJsonb().get("emotion_line");
        assertThat(mergedLine)
                .as("emotion_line 上限是 PROFILE_EMOTION_LINE_MAX（50），超过应丢最早")
                .hasSize(MemoryConstants.PROFILE_EMOTION_LINE_MAX);
        assertThat(mergedLine.get(0).get("signal"))
                .as("最早一条（情绪#0）应被丢弃")
                .isEqualTo("情绪#1");
        assertThat(mergedLine.get(mergedLine.size() - 1).get("signal"))
                .as("最新一条应是新抽取的 emotion_signal")
                .isEqualTo("焦虑");
        assertThat(mergedLine.get(mergedLine.size() - 1))
                .containsKey("at");
    }

    @Test
    void merge_shouldSkipBlankEmotionSignal() {
        service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.validProfile();
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(MemoryProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ProfileExtraction extraction = new ProfileExtraction(null, null, null, "   ");
        MemoryProfileEntity result = service.merge(1L, 1L, extraction);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> line =
                (List<Map<String, Object>>) result.getProfileJsonb().get("emotion_line");
        assertThat(line).as("空白 emotion_signal 不应被记录").isEmpty();
    }

    // ===== initial profile creation =====

    @Test
    void merge_shouldCreateInitialProfileWhenNotExists() {
        service = newService();
        when(repository.findByUserIdAndCharacterId(2L, 3L)).thenReturn(Optional.empty());
        when(repository.save(any(MemoryProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> basicUpdates = new HashMap<>();
        basicUpdates.put("name", "新用户");
        ProfileExtraction extraction = new ProfileExtraction(basicUpdates, null, null, null);

        MemoryProfileEntity result = service.merge(2L, 3L, extraction);

        assertThat(result.getUserId()).isEqualTo(2L);
        assertThat(result.getCharacterId()).isEqualTo(3L);
        Map<String, Object> profile = result.getProfileJsonb();
        assertThat(profile)
                .as("初始 schema 必须包含 4 个顶层 key")
                .containsKeys("basic_info", "preferences", "events", "emotion_line");
        @SuppressWarnings("unchecked")
        Map<String, Object> basicInfo = (Map<String, Object>) profile.get("basic_info");
        assertThat(basicInfo.get("name"))
                .as("初始 profile 的 name 应直接写入新值（之前为 null）")
                .isEqualTo("新用户");
    }

    // ===== optimistic lock retry =====

    @Test
    void merge_shouldRetryOnceOnOptimisticLockConflictAndSucceed() {
        service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.validProfile();
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        // 第一次抛冲突，第二次成功返回 entity
        when(repository.save(any(MemoryProfileEntity.class)))
                .thenThrow(new OptimisticLockingFailureException("conflict 1"))
                .thenAnswer(inv -> inv.getArgument(0));

        ProfileExtraction extraction = new ProfileExtraction(null, null, null, "开心");

        MemoryProfileEntity result = service.merge(1L, 1L, extraction);

        assertThat(result).isNotNull();
        // 重试一次：findByUserIdAndCharacterId 调了 2 次，save 调了 2 次
        verify(repository, times(2)).findByUserIdAndCharacterId(1L, 1L);
        verify(repository, times(2)).save(any(MemoryProfileEntity.class));
    }

    @Test
    void merge_shouldThrowBusinessExceptionAfterRetryFailure() {
        service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.validProfile();
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(MemoryProfileEntity.class)))
                .thenThrow(new OptimisticLockingFailureException("conflict 1"))
                .thenThrow(new OptimisticLockingFailureException("conflict 2"));

        ProfileExtraction extraction = new ProfileExtraction(null, null, null, "焦虑");

        ArgumentCaptor<MemoryProfileEntity> savedCaptor =
                ArgumentCaptor.forClass(MemoryProfileEntity.class);
        assertThatThrownBy(() -> service.merge(1L, 1L, extraction))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrCode())
                        .isEqualTo(MemoryErrCode.PROFILE_MERGE_CONFLICT));
        verify(repository, times(2)).save(savedCaptor.capture());
    }

    @Test
    void merge_shouldDoNothingWhenAllExtractionFieldsAreNull() {
        service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.profileWithName("小明");
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(MemoryProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ProfileExtraction extraction = new ProfileExtraction(null, null, null, null);
        MemoryProfileEntity result = service.merge(1L, 1L, extraction);

        // 所有字段为 null 时不该改任何东西
        @SuppressWarnings("unchecked")
        Map<String, Object> basicInfo = (Map<String, Object>) result.getProfileJsonb().get("basic_info");
        assertThat(basicInfo.get("name")).isEqualTo("小明");
        verify(repository, never()).findById(any());
    }
}
