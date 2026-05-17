package com.sanyan.memory.event;

import com.sanyan.chat.ChatApi;
import com.sanyan.chat.SenderType;
import com.sanyan.chat.dto.MessageDto;
import com.sanyan.chat.event.MessagePersistedEvent;
import com.sanyan.common.cache.KvCache;
import com.sanyan.memory.MemoryConstants;
import com.sanyan.memory.internal.profile.MemoryProfileRefreshService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan 2.5：{@link UserMessageProfileRefreshListener} Mockito 单测。
 *
 * <p>覆盖路径：
 * <ul>
 *   <li>role=user 事件 + 节流未命中 → 调 Refresh（用户 id、角色 id、时间正序消息）</li>
 *   <li>role=ai 事件 → 直接 return（不查 Redis、不调 Refresh）</li>
 *   <li>role=user 事件 + 节流命中（KvCache.setIfAbsent 返回 false） → 跳过</li>
 *   <li>取最近 {@link UserMessageProfileRefreshListener#RECENT_MESSAGES_FOR_REFRESH} 条消息且反转为时间正序</li>
 *   <li>throttle key 格式 {@code sanyan:memory:profile:throttle:{userId}:{characterId}}</li>
 *   <li>throttle TTL = {@link MemoryConstants#PROFILE_EXTRACT_THROTTLE_MINUTES} 分钟</li>
 *   <li>Refresh 抛异常时 listener 吞掉（不向外抛，不影响主对话）</li>
 *   <li>role 大写也识别为 user（防御性匹配）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserMessageProfileRefreshListenerTest {

    @Mock
    private MemoryProfileRefreshService refreshService;
    @Mock
    private ChatApi chatApi;
    @Mock
    private KvCache kvCache;

    @InjectMocks
    private UserMessageProfileRefreshListener listener;

    private static final Long USER_ID = 200L;
    private static final Long CHARACTER_ID = 1L;
    private static final Long MESSAGE_ID = 500L;

    @Test
    void onMessagePersisted_userRole_throttleAcquired_triggersRefresh() {
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        List<MessageDto> recentDesc = buildMessagesDesc(MESSAGE_ID,
                UserMessageProfileRefreshListener.RECENT_MESSAGES_FOR_REFRESH);
        when(chatApi.listRecentByUser(eq(USER_ID), anyInt()))
                .thenReturn(recentDesc);

        listener.onMessagePersisted(userEvent(MESSAGE_ID));

        // Refresh 收到的应是时间正序（id 升序）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(refreshService).refresh(eq(USER_ID), eq(CHARACTER_ID), captor.capture());
        List<MessageDto> passed = captor.getValue();
        assertThat(passed).hasSize(UserMessageProfileRefreshListener.RECENT_MESSAGES_FOR_REFRESH);
        assertThat(passed.get(0).id()).isLessThan(passed.get(passed.size() - 1).id());
    }

    @Test
    void onMessagePersisted_aiRole_isIgnored() {
        listener.onMessagePersisted(new MessagePersistedEvent(MESSAGE_ID, USER_ID, CHARACTER_ID, SenderType.AI));

        verify(kvCache, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(chatApi, never()).listRecentByUser(anyLong(), anyInt());
        verify(refreshService, never()).refresh(anyLong(), anyLong(), anyList());
    }

    @Test
    void onMessagePersisted_throttleMiss_skipsRefresh() {
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        listener.onMessagePersisted(userEvent(MESSAGE_ID));

        verify(chatApi, never()).listRecentByUser(anyLong(), anyInt());
        verify(refreshService, never()).refresh(anyLong(), anyLong(), anyList());
    }

    @Test
    void onMessagePersisted_buildsCorrectThrottleKeyAndTtl() {
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        listener.onMessagePersisted(userEvent(MESSAGE_ID));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(kvCache).setIfAbsent(keyCaptor.capture(), anyString(), ttlCaptor.capture());

        assertThat(keyCaptor.getValue())
                .isEqualTo("sanyan:memory:profile:throttle:" + USER_ID + ":" + CHARACTER_ID);
        assertThat(ttlCaptor.getValue())
                .isEqualTo(Duration.ofMinutes(MemoryConstants.PROFILE_EXTRACT_THROTTLE_MINUTES));
    }

    @Test
    void onMessagePersisted_requestsRecentNMessages() {
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(chatApi.listRecentByUser(eq(USER_ID), anyInt()))
                .thenReturn(Collections.emptyList());

        listener.onMessagePersisted(userEvent(MESSAGE_ID));

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(chatApi).listRecentByUser(eq(USER_ID), limitCaptor.capture());
        assertThat(limitCaptor.getValue())
                .isEqualTo(UserMessageProfileRefreshListener.RECENT_MESSAGES_FOR_REFRESH);
    }

    @Test
    void onMessagePersisted_swallowsRefreshException_doesNotThrow() {
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(chatApi.listRecentByUser(eq(USER_ID), anyInt()))
                .thenReturn(buildMessagesDesc(MESSAGE_ID,
                        UserMessageProfileRefreshListener.RECENT_MESSAGES_FOR_REFRESH));
        when(refreshService.refresh(anyLong(), anyLong(), anyList()))
                .thenThrow(new RuntimeException("LLM 网络异常"));

        // 不应向外抛
        listener.onMessagePersisted(userEvent(MESSAGE_ID));

        verify(refreshService, times(1)).refresh(eq(USER_ID), eq(CHARACTER_ID), anyList());
    }

    @Test
    void onMessagePersisted_userRoleCaseInsensitive() {
        // role 字段大写也应被识别为 user（防御性匹配）
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(chatApi.listRecentByUser(eq(USER_ID), anyInt()))
                .thenReturn(Collections.emptyList());

        listener.onMessagePersisted(new MessagePersistedEvent(MESSAGE_ID, USER_ID, CHARACTER_ID, "USER"));

        verify(kvCache).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    // -------- helpers --------

    private static MessagePersistedEvent userEvent(Long messageId) {
        return new MessagePersistedEvent(messageId, USER_ID, CHARACTER_ID, SenderType.USER);
    }

    /** 构造按 id 降序的消息列表（模拟 ChatApi.listRecentByUser 的返回顺序）。 */
    private static List<MessageDto> buildMessagesDesc(long latestId, int count) {
        List<MessageDto> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new MessageDto(
                    latestId - i,
                    USER_ID,
                    i % 2 == 0 ? SenderType.USER : SenderType.AI,
                    "msg-" + (latestId - i),
                    null));
        }
        return list;
    }
}
