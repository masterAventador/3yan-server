package com.sanyan.memory.internal.profile;

import com.sanyan.common.error.BusinessException;
import com.sanyan.memory.internal.MemoryConstants;
import com.sanyan.memory.internal.MemoryErrCode;
import com.sanyan.memory.internal.profile.dto.ProfileExtraction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan 2 Task O3：结构化档案「增量 merge」服务。
 *
 * <p>读出当前 {@link MemoryProfileEntity} → 在 Java 端按 plan 规则把 {@link ProfileExtraction} 合并到
 * profile JSONB → 写回（依赖 Entity {@code @Version} 字段做乐观锁）。
 *
 * <p><b>为什么 Java 端 merge 而不是 PG {@code jsonb_set}：</b>规则较复杂（name 仅首写、
 * preferences 去重、emotion_line 长度截断），写在 PG 表达式里很难单测；Java 端实现可以 Mockito 单测。
 *
 * <p><b>合并规则（plan §O3）：</b>
 * <ul>
 *   <li>{@code basic_info.name}：当前为 {@code null} 时写入；否则忽略（防 LLM 反复改名）</li>
 *   <li>{@code basic_info.age / occupation / hometown}：每次抽取允许覆盖（搬家 / 换工作场景）</li>
 *   <li>{@code preferences.foods / movies / colors / music}：append + 小写规范化后去重</li>
 *   <li>{@code events}：追加，每条 event 若没带 {@code extracted_at} 由本类补 {@link Instant#now()}</li>
 *   <li>{@code emotion_line}：每次追加一条 {signal, at}，仅保留最近
 *       {@link MemoryConstants#PROFILE_EMOTION_LINE_MAX}（=50）条，超过丢最早</li>
 * </ul>
 *
 * <p><b>乐观锁处理：</b>{@link OptimisticLockingFailureException} 时重试 <b>1 次</b>（重新读 → 重新 merge → 重新保存）。
 * 再次冲突抛 {@link BusinessException}({@link MemoryErrCode#PROFILE_MERGE_CONFLICT})，由调用方（O4 listener）
 * 决定重新入队或丢弃，service 层不再吞掉。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryProfileMergeService {

    /** 最大乐观锁重试次数（plan §O3：重试 1 次后失败抛异常）。 */
    private static final int MAX_OPTIMISTIC_LOCK_RETRIES = 1;

    /** {@link ProfileExtraction#basicInfoUpdates()} 中 name 字段的 key —— 与其他可覆盖字段语义不同，单独命名。 */
    private static final String BASIC_INFO_NAME_KEY = "name";

    private final MemoryProfileRepository repository;

    /**
     * 把 {@code extraction} 增量合并进 (userId, characterId) 对应的 profile。
     * 不存在则创建初始空 schema。
     */
    public MemoryProfileEntity merge(Long userId, Long characterId, ProfileExtraction extraction) {
        return mergeWithRetry(userId, characterId, extraction, MAX_OPTIMISTIC_LOCK_RETRIES);
    }

    private MemoryProfileEntity mergeWithRetry(
            Long userId, Long characterId, ProfileExtraction extraction, int retriesLeft) {
        try {
            MemoryProfileEntity entity = repository
                    .findByUserIdAndCharacterId(userId, characterId)
                    .orElseGet(() -> createInitialProfile(userId, characterId));
            applyMerge(entity, extraction);
            return repository.save(entity);
        } catch (OptimisticLockingFailureException e) {
            if (retriesLeft <= 0) {
                log.error("Profile merge 冲突重试耗尽 user={} char={}", userId, characterId, e);
                throw new BusinessException(MemoryErrCode.PROFILE_MERGE_CONFLICT);
            }
            log.warn("Profile merge 乐观锁冲突，重试 (剩 {} 次): user={} char={}",
                    retriesLeft, userId, characterId);
            return mergeWithRetry(userId, characterId, extraction, retriesLeft - 1);
        }
    }

    private static MemoryProfileEntity createInitialProfile(Long userId, Long characterId) {
        MemoryProfileEntity e = new MemoryProfileEntity();
        e.setUserId(userId);
        e.setCharacterId(characterId);
        e.setProfileJsonb(initialProfileSchema());
        return e;
    }

    /**
     * 初始 JSONB schema（对齐 V6__init_memory_profiles.sql 头注释 + plan §O3）。
     * 用 {@link LinkedHashMap} 保留字段顺序，便于人眼对照 / 日志阅读。
     */
    private static Map<String, Object> initialProfileSchema() {
        Map<String, Object> basicInfo = new LinkedHashMap<>();
        basicInfo.put(BASIC_INFO_NAME_KEY, null);
        basicInfo.put("age", null);
        basicInfo.put("occupation", null);
        basicInfo.put("hometown", null);

        Map<String, Object> preferences = new LinkedHashMap<>();
        preferences.put("foods", new ArrayList<>());
        preferences.put("movies", new ArrayList<>());
        preferences.put("colors", new ArrayList<>());
        preferences.put("music", new ArrayList<>());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("basic_info", basicInfo);
        root.put("preferences", preferences);
        root.put("events", new ArrayList<>());
        root.put("emotion_line", new ArrayList<>());
        return root;
    }

    private static void applyMerge(MemoryProfileEntity entity, ProfileExtraction extraction) {
        Map<String, Object> profile = entity.getProfileJsonb();
        mergeBasicInfo(profile, extraction.basicInfoUpdates());
        mergePreferences(profile, extraction.preferencesAppended());
        mergeEvents(profile, extraction.eventsAppended());
        mergeEmotionLine(profile, extraction.emotionSignal());
        entity.setProfileJsonb(profile);
    }

    @SuppressWarnings("unchecked")
    private static void mergeBasicInfo(Map<String, Object> profile, Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        Map<String, Object> basicInfo = (Map<String, Object>) profile.computeIfAbsent(
                "basic_info", k -> new LinkedHashMap<>());
        updates.forEach((field, value) -> {
            if (value == null) {
                return;
            }
            if (BASIC_INFO_NAME_KEY.equals(field)) {
                // name 只在当前为 null 时写入（防 LLM 反复改名）
                if (basicInfo.get(BASIC_INFO_NAME_KEY) == null) {
                    basicInfo.put(BASIC_INFO_NAME_KEY, value);
                }
            } else {
                // age / occupation / hometown 允许每次覆盖
                basicInfo.put(field, value);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void mergePreferences(
            Map<String, Object> profile, Map<String, List<String>> appendedByCategory) {
        if (appendedByCategory == null || appendedByCategory.isEmpty()) {
            return;
        }
        Map<String, Object> preferences = (Map<String, Object>) profile.computeIfAbsent(
                "preferences", k -> new LinkedHashMap<>());
        appendedByCategory.forEach((category, newItems) -> {
            if (newItems == null || newItems.isEmpty()) {
                return;
            }
            List<String> current = (List<String>) preferences.computeIfAbsent(
                    category, k -> new ArrayList<>());
            for (String item : newItems) {
                if (item == null) {
                    continue;
                }
                String normalized = item.toLowerCase().trim();
                if (normalized.isEmpty()) {
                    continue;
                }
                boolean exists = current.stream()
                        .anyMatch(existing -> existing.toLowerCase().trim().equals(normalized));
                if (!exists) {
                    current.add(item);
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void mergeEvents(Map<String, Object> profile, List<Map<String, Object>> appended) {
        if (appended == null || appended.isEmpty()) {
            return;
        }
        List<Map<String, Object>> events = (List<Map<String, Object>>) profile.computeIfAbsent(
                "events", k -> new ArrayList<>());
        String now = Instant.now().toString();
        for (Map<String, Object> evt : appended) {
            if (evt == null) {
                continue;
            }
            Map<String, Object> withTimestamp = new LinkedHashMap<>(evt);
            withTimestamp.putIfAbsent("extracted_at", now);
            events.add(withTimestamp);
        }
    }

    @SuppressWarnings("unchecked")
    private static void mergeEmotionLine(Map<String, Object> profile, String emotionSignal) {
        if (emotionSignal == null || emotionSignal.isBlank()) {
            return;
        }
        List<Map<String, Object>> line = (List<Map<String, Object>>) profile.computeIfAbsent(
                "emotion_line", k -> new ArrayList<>());
        Map<String, Object> emotion = new LinkedHashMap<>();
        emotion.put("signal", emotionSignal);
        emotion.put("at", Instant.now().toString());
        line.add(emotion);
        // 仅保留最近 PROFILE_EMOTION_LINE_MAX 条
        while (line.size() > MemoryConstants.PROFILE_EMOTION_LINE_MAX) {
            line.remove(0);
        }
    }

}
