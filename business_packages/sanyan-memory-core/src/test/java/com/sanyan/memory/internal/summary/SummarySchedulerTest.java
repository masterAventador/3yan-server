package com.sanyan.memory.internal.summary;

import com.sanyan.chat.event.MessagePersistedEvent;
import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.MessageRepository;
import com.sanyan.chat.internal.SenderType;
import com.sanyan.memory.internal.MemoryConstants;
import com.sanyan.memory.internal.summary.fixtures.MemorySummaryTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan 2 Task N3：SummaryScheduler 单元测试。
 *
 * <p>纯 Mockito 单测，验证 {@link SummaryScheduler#onMessagePersisted} 在各种新消息累积量
 * 下的触发/不触发行为，以及"首次摘要"和"异常吞掉"场景。
 *
 * <p>测试约束（与 MemoryConstants.SUMMARY_TRIGGER_THRESHOLD = 30 对齐）：
 * <ul>
 *   <li>新消息累积 &lt; 30 → 不触发 summarize</li>
 *   <li>新消息累积 == 29 → 不触发（阈值是 ≥ 30）</li>
 *   <li>新消息累积 == 30 → 触发，调 service.summarize + 保存新 MemorySummaryEntity</li>
 *   <li>新消息累积 &gt; 30 → 触发</li>
 *   <li>首次摘要（无历史 summary）→ sinceMessageId = 0，用所有该 user 的消息</li>
 *   <li>summarize 抛异常 → listener 吞掉（log.error），不向外抛（不能影响主对话）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SummarySchedulerTest {

    @Mock
    private MemorySummaryRepository summaryRepository;
    @Mock
    private MemorySummaryService summaryService;
    @Mock
    private MessageRepository messageRepository;

    @InjectMocks
    private SummaryScheduler scheduler;

    private static final Long USER_ID = 100L;
    private static final Long CHARACTER_ID = 1L;

    @Test
    void onMessagePersisted_shouldNotTrigger_whenNewMessagesBelowThreshold() {
        // 已有最新 summary：覆盖到 message_id = 100
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.of(summaryEndingAt(100L)));
        // 自上次摘要以来新消息只有 10 条 (< 30)
        when(messageRepository.countByUserIdAndIdGreaterThan(USER_ID, 100L)).thenReturn(10L);

        scheduler.onMessagePersisted(eventForMessage(101L));

        verify(summaryService, never()).summarize(anyList());
        verify(summaryRepository, never()).save(any(MemorySummaryEntity.class));
    }

    @Test
    void onMessagePersisted_shouldNotTrigger_atThresholdMinusOne() {
        // 自上次摘要以来累积 29 条 —— 边界：阈值是 ≥ 30，29 不触发
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.of(summaryEndingAt(100L)));
        when(messageRepository.countByUserIdAndIdGreaterThan(USER_ID, 100L)).thenReturn(29L);

        scheduler.onMessagePersisted(eventForMessage(129L));

        verify(summaryService, never()).summarize(anyList());
        verify(summaryRepository, never()).save(any(MemorySummaryEntity.class));
    }

    @Test
    void onMessagePersisted_shouldTrigger_atThreshold() {
        // 自上次摘要以来累积 30 条 —— 边界：阈值 ≥ 30，30 触发
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.of(summaryEndingAt(100L)));
        when(messageRepository.countByUserIdAndIdGreaterThan(USER_ID, 100L))
                .thenReturn((long) MemoryConstants.SUMMARY_TRIGGER_THRESHOLD);
        List<MessageEntity> newMessages = buildMessages(101L, MemoryConstants.SUMMARY_TRIGGER_THRESHOLD);
        when(messageRepository.findByUserIdAndIdGreaterThanOrderByIdAsc(USER_ID, 100L))
                .thenReturn(newMessages);
        when(summaryService.summarize(newMessages)).thenReturn("阈值摘要内容");

        scheduler.onMessagePersisted(eventForMessage(130L));

        verify(summaryService, times(1)).summarize(newMessages);
        ArgumentCaptor<MemorySummaryEntity> captor = ArgumentCaptor.forClass(MemorySummaryEntity.class);
        verify(summaryRepository).save(captor.capture());
        MemorySummaryEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getCharacterId()).isEqualTo(CHARACTER_ID);
        assertThat(saved.getPeriodStartMessageId()).isEqualTo(101L);
        assertThat(saved.getPeriodEndMessageId()).isEqualTo(130L);
        assertThat(saved.getMessageCount()).isEqualTo(MemoryConstants.SUMMARY_TRIGGER_THRESHOLD);
        assertThat(saved.getSummaryText()).isEqualTo("阈值摘要内容");
    }

    @Test
    void onMessagePersisted_shouldTrigger_whenNewMessagesAboveThreshold() {
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.of(summaryEndingAt(50L)));
        when(messageRepository.countByUserIdAndIdGreaterThan(USER_ID, 50L)).thenReturn(35L);
        List<MessageEntity> newMessages = buildMessages(51L, 35);
        when(messageRepository.findByUserIdAndIdGreaterThanOrderByIdAsc(USER_ID, 50L))
                .thenReturn(newMessages);
        when(summaryService.summarize(newMessages)).thenReturn("超阈值摘要");

        scheduler.onMessagePersisted(eventForMessage(85L));

        verify(summaryService).summarize(newMessages);
        ArgumentCaptor<MemorySummaryEntity> captor = ArgumentCaptor.forClass(MemorySummaryEntity.class);
        verify(summaryRepository).save(captor.capture());
        assertThat(captor.getValue().getMessageCount()).isEqualTo(35);
        assertThat(captor.getValue().getPeriodStartMessageId()).isEqualTo(51L);
        assertThat(captor.getValue().getPeriodEndMessageId()).isEqualTo(85L);
    }

    @Test
    void onMessagePersisted_firstSummary_usesAllMessagesSinceZero() {
        // 没有历史 summary —— sinceMessageId 应回退到 0
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.empty());
        when(messageRepository.countByUserIdAndIdGreaterThan(USER_ID, 0L))
                .thenReturn((long) MemoryConstants.SUMMARY_TRIGGER_THRESHOLD);
        List<MessageEntity> allMessages = buildMessages(1L, MemoryConstants.SUMMARY_TRIGGER_THRESHOLD);
        when(messageRepository.findByUserIdAndIdGreaterThanOrderByIdAsc(USER_ID, 0L))
                .thenReturn(allMessages);
        when(summaryService.summarize(allMessages)).thenReturn("首次摘要");

        scheduler.onMessagePersisted(eventForMessage(30L));

        verify(messageRepository).countByUserIdAndIdGreaterThan(USER_ID, 0L);
        verify(messageRepository).findByUserIdAndIdGreaterThanOrderByIdAsc(USER_ID, 0L);
        verify(summaryService).summarize(allMessages);
        ArgumentCaptor<MemorySummaryEntity> captor = ArgumentCaptor.forClass(MemorySummaryEntity.class);
        verify(summaryRepository).save(captor.capture());
        assertThat(captor.getValue().getPeriodStartMessageId()).isEqualTo(1L);
        assertThat(captor.getValue().getPeriodEndMessageId()).isEqualTo(30L);
    }

    @Test
    void onMessagePersisted_swallowsServiceException_doesNotThrow() {
        // summarize 抛异常 —— listener 必须吞掉，不能影响主对话
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.of(summaryEndingAt(100L)));
        when(messageRepository.countByUserIdAndIdGreaterThan(USER_ID, 100L))
                .thenReturn((long) MemoryConstants.SUMMARY_TRIGGER_THRESHOLD);
        List<MessageEntity> newMessages = buildMessages(101L, MemoryConstants.SUMMARY_TRIGGER_THRESHOLD);
        when(messageRepository.findByUserIdAndIdGreaterThanOrderByIdAsc(USER_ID, 100L))
                .thenReturn(newMessages);
        when(summaryService.summarize(newMessages))
                .thenThrow(new RuntimeException("LLM 调用失败"));

        // 不应抛出
        scheduler.onMessagePersisted(eventForMessage(130L));

        verify(summaryRepository, never()).save(any(MemorySummaryEntity.class));
    }

    // -------- helpers --------

    private static MessagePersistedEvent eventForMessage(Long messageId) {
        return new MessagePersistedEvent(messageId, USER_ID, CHARACTER_ID, SenderType.USER);
    }

    private static MemorySummaryEntity summaryEndingAt(Long lastMessageId) {
        MemorySummaryEntity e = MemorySummaryTestFixtures.validSummary();
        e.setPeriodEndMessageId(lastMessageId);
        return e;
    }

    /** 构造 count 条连续 id 的消息（从 startId 开始递增）。 */
    private static List<MessageEntity> buildMessages(long startId, int count) {
        List<MessageEntity> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MessageEntity m = new MessageEntity();
            m.setId(startId + i);
            m.setUserId(USER_ID);
            m.setSenderType(i % 2 == 0 ? SenderType.USER : SenderType.AI);
            m.setContent("msg-" + (startId + i));
            list.add(m);
        }
        return list;
    }
}
