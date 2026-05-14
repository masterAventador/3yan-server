package com.sanyan.memory.event;

import com.sanyan.chat.event.MessagePersistedEvent;
import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.MessageRepository;
import com.sanyan.chat.internal.SenderType;
import com.sanyan.common.cache.KvCache;
import com.sanyan.memory.internal.MemoryConstants;
import com.sanyan.memory.internal.profile.MemoryProfileExtractService;
import com.sanyan.memory.internal.profile.MemoryProfileMergeService;
import com.sanyan.memory.internal.profile.dto.ProfileExtraction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan 2 Task O4：{@link UserMessageProfileExtractListener} Mockito 单测。
 *
 * <p>覆盖路径（与 plan §O4 对齐）：
 * <ul>
 *   <li>role=user 事件 + 节流未命中 → 调 Extract → 调 Merge</li>
 *   <li>role=ai 事件 → 直接 return（不查 Redis、不调 Extract）</li>
 *   <li>role=user 事件 + 节流命中（KvCache.setIfAbsent 返回 false） → 跳过</li>
 *   <li>Extract 返回 null（LLM 失败 / 无可抽内容） → 不调 Merge</li>
 *   <li>取最近 {@link UserMessageProfileExtractListener#RECENT_MESSAGES_FOR_EXTRACT} 条消息且反转为时间正序</li>
 *   <li>throttle key 格式 {@code sanyan:memory:profile:throttle:{userId}:{characterId}}</li>
 *   <li>throttle TTL = {@link MemoryConstants#PROFILE_EXTRACT_THROTTLE_MINUTES} 分钟</li>
 *   <li>Extract / Merge 抛异常时 listener 吞掉（不向外抛，不影响主对话）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserMessageProfileExtractListenerTest {

    @Mock
    private MemoryProfileExtractService extractService;
    @Mock
    private MemoryProfileMergeService mergeService;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private KvCache kvCache;

    @InjectMocks
    private UserMessageProfileExtractListener listener;

    private static final Long USER_ID = 200L;
    private static final Long CHARACTER_ID = 1L;
    private static final Long MESSAGE_ID = 500L;

    @Test
    void onMessagePersisted_userRole_throttleAcquired_triggersExtractAndMerge() {
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        List<MessageEntity> recentDesc = buildMessagesDesc(MESSAGE_ID, UserMessageProfileExtractListener.RECENT_MESSAGES_FOR_EXTRACT);
        when(messageRepository.findByUserIdOrderByIdDesc(eq(USER_ID), any(Pageable.class)))
                .thenReturn(recentDesc);
        ProfileExtraction extraction = new ProfileExtraction(null, null, null, "开心");
        when(extractService.extract(anyList())).thenReturn(extraction);

        listener.onMessagePersisted(userEvent(MESSAGE_ID));

        // Extract 收到的应是时间正序（id 升序）
        ArgumentCaptor<List<MessageEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(extractService).extract(captor.capture());
        List<MessageEntity> passed = captor.getValue();
        assertThat(passed).hasSize(UserMessageProfileExtractListener.RECENT_MESSAGES_FOR_EXTRACT);
        // 第一条 id 最小，最后一条 id 最大
        assertThat(passed.get(0).getId()).isLessThan(passed.get(passed.size() - 1).getId());
        // Merge 用相同的 user / character / extraction
        verify(mergeService).merge(USER_ID, CHARACTER_ID, extraction);
    }

    @Test
    void onMessagePersisted_aiRole_isIgnored() {
        listener.onMessagePersisted(new MessagePersistedEvent(MESSAGE_ID, USER_ID, CHARACTER_ID, SenderType.AI));

        verify(kvCache, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(messageRepository, never()).findByUserIdOrderByIdDesc(anyLong(), any(Pageable.class));
        verify(extractService, never()).extract(anyList());
        verify(mergeService, never()).merge(anyLong(), anyLong(), any(ProfileExtraction.class));
    }

    @Test
    void onMessagePersisted_throttleMiss_skipsExtractAndMerge() {
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        listener.onMessagePersisted(userEvent(MESSAGE_ID));

        verify(messageRepository, never()).findByUserIdOrderByIdDesc(anyLong(), any(Pageable.class));
        verify(extractService, never()).extract(anyList());
        verify(mergeService, never()).merge(anyLong(), anyLong(), any(ProfileExtraction.class));
    }

    @Test
    void onMessagePersisted_extractReturnsNull_doesNotCallMerge() {
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(messageRepository.findByUserIdOrderByIdDesc(eq(USER_ID), any(Pageable.class)))
                .thenReturn(buildMessagesDesc(MESSAGE_ID, UserMessageProfileExtractListener.RECENT_MESSAGES_FOR_EXTRACT));
        when(extractService.extract(anyList())).thenReturn(null);

        listener.onMessagePersisted(userEvent(MESSAGE_ID));

        verify(extractService, times(1)).extract(anyList());
        verify(mergeService, never()).merge(anyLong(), anyLong(), any(ProfileExtraction.class));
    }

    @Test
    void onMessagePersisted_buildsCorrectThrottleKeyAndTtl() {
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        listener.onMessagePersisted(userEvent(MESSAGE_ID));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(kvCache).setIfAbsent(keyCaptor.capture(), anyString(), ttlCaptor.capture());

        // key 格式：sanyan:memory:profile:throttle:{userId}:{characterId}
        assertThat(keyCaptor.getValue())
                .isEqualTo("sanyan:memory:profile:throttle:" + USER_ID + ":" + CHARACTER_ID);
        // TTL = PROFILE_EXTRACT_THROTTLE_MINUTES 分钟
        assertThat(ttlCaptor.getValue())
                .isEqualTo(Duration.ofMinutes(MemoryConstants.PROFILE_EXTRACT_THROTTLE_MINUTES));
    }

    @Test
    void onMessagePersisted_requestsRecentNMessages() {
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(messageRepository.findByUserIdOrderByIdDesc(eq(USER_ID), any(Pageable.class)))
                .thenReturn(Collections.emptyList());
        when(extractService.extract(anyList())).thenReturn(null);

        listener.onMessagePersisted(userEvent(MESSAGE_ID));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(messageRepository).findByUserIdOrderByIdDesc(eq(USER_ID), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(0);
        assertThat(pageable.getPageSize())
                .isEqualTo(UserMessageProfileExtractListener.RECENT_MESSAGES_FOR_EXTRACT);
    }

    @Test
    void onMessagePersisted_swallowsExtractException_doesNotThrow() {
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(messageRepository.findByUserIdOrderByIdDesc(eq(USER_ID), any(Pageable.class)))
                .thenReturn(buildMessagesDesc(MESSAGE_ID, UserMessageProfileExtractListener.RECENT_MESSAGES_FOR_EXTRACT));
        when(extractService.extract(anyList())).thenThrow(new RuntimeException("LLM 网络异常"));

        // 不应向外抛
        listener.onMessagePersisted(userEvent(MESSAGE_ID));

        verify(mergeService, never()).merge(anyLong(), anyLong(), any(ProfileExtraction.class));
    }

    @Test
    void onMessagePersisted_swallowsMergeException_doesNotThrow() {
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(messageRepository.findByUserIdOrderByIdDesc(eq(USER_ID), any(Pageable.class)))
                .thenReturn(buildMessagesDesc(MESSAGE_ID, UserMessageProfileExtractListener.RECENT_MESSAGES_FOR_EXTRACT));
        ProfileExtraction extraction = new ProfileExtraction(null, null, null, "焦虑");
        when(extractService.extract(anyList())).thenReturn(extraction);
        when(mergeService.merge(anyLong(), anyLong(), any(ProfileExtraction.class)))
                .thenThrow(new RuntimeException("乐观锁冲突"));

        // 不应向外抛
        listener.onMessagePersisted(userEvent(MESSAGE_ID));

        verify(mergeService).merge(USER_ID, CHARACTER_ID, extraction);
    }

    @Test
    void onMessagePersisted_userRoleCaseInsensitive() {
        // role 字段大写也应被识别为 user（防御性匹配）
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(messageRepository.findByUserIdOrderByIdDesc(eq(USER_ID), any(Pageable.class)))
                .thenReturn(Collections.emptyList());
        when(extractService.extract(anyList())).thenReturn(null);

        listener.onMessagePersisted(new MessagePersistedEvent(MESSAGE_ID, USER_ID, CHARACTER_ID, "USER"));

        verify(kvCache).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    // -------- helpers --------

    private static MessagePersistedEvent userEvent(Long messageId) {
        return new MessagePersistedEvent(messageId, USER_ID, CHARACTER_ID, SenderType.USER);
    }

    /** 构造按 id 降序的消息列表（模拟 Repository.findByUserIdOrderByIdDesc 的返回顺序）。 */
    private static List<MessageEntity> buildMessagesDesc(long latestId, int count) {
        List<MessageEntity> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MessageEntity m = new MessageEntity();
            m.setId(latestId - i);
            m.setUserId(USER_ID);
            m.setSenderType(i % 2 == 0 ? SenderType.USER : SenderType.AI);
            m.setContent("msg-" + (latestId - i));
            list.add(m);
        }
        return list;
    }

}
