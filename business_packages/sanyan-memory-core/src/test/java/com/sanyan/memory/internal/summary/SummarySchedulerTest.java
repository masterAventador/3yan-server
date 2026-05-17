package com.sanyan.memory.internal.summary;

import com.sanyan.chat.ChatApi;
import com.sanyan.chat.SenderType;
import com.sanyan.chat.dto.MessageDto;
import com.sanyan.chat.event.MessagePersistedEvent;
import com.sanyan.common.cache.KvCache;
import com.sanyan.memory.MemoryConstants;
import com.sanyan.memory.internal.summary.fixtures.MemorySummaryTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    private ChatApi chatApi;
    @Mock
    private KvCache kvCache;

    @InjectMocks
    private SummaryScheduler scheduler;

    private static final Long USER_ID = 100L;
    private static final Long CHARACTER_ID = 1L;

    /** 默认让所有用例都能拿到锁；个别用例覆盖此行为模拟并发抢锁失败。 */
    private void givenLockAcquired() {
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    }

    @Test
    void onMessagePersisted_shouldNotTrigger_whenNewMessagesBelowThreshold() {
        givenLockAcquired();
        // 已有最新 summary：覆盖到 message_id = 100
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.of(summaryEndingAt(100L)));
        // 自上次摘要以来新消息只有 10 条 (< 30)
        when(chatApi.countSinceMessageId(USER_ID, 100L)).thenReturn(10L);

        scheduler.onMessagePersisted(eventForMessage(101L));

        verify(summaryService, never()).summarize(anyList());
        verify(summaryRepository, never()).save(any(MemorySummaryEntity.class));
    }

    @Test
    void onMessagePersisted_shouldNotTrigger_atThresholdMinusOne() {
        givenLockAcquired();
        // 自上次摘要以来累积 29 条 —— 边界：阈值是 ≥ 30，29 不触发
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.of(summaryEndingAt(100L)));
        when(chatApi.countSinceMessageId(USER_ID, 100L)).thenReturn(29L);

        scheduler.onMessagePersisted(eventForMessage(129L));

        verify(summaryService, never()).summarize(anyList());
        verify(summaryRepository, never()).save(any(MemorySummaryEntity.class));
    }

    @Test
    void onMessagePersisted_shouldTrigger_atThreshold() {
        givenLockAcquired();
        // 自上次摘要以来累积 30 条 —— 边界：阈值 ≥ 30，30 触发
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.of(summaryEndingAt(100L)));
        when(chatApi.countSinceMessageId(USER_ID, 100L))
                .thenReturn((long) MemoryConstants.SUMMARY_TRIGGER_THRESHOLD);
        List<MessageDto> newMessages = buildMessages(101L, MemoryConstants.SUMMARY_TRIGGER_THRESHOLD);
        when(chatApi.listSinceMessageId(USER_ID, 100L))
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
        givenLockAcquired();
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.of(summaryEndingAt(50L)));
        when(chatApi.countSinceMessageId(USER_ID, 50L)).thenReturn(35L);
        List<MessageDto> newMessages = buildMessages(51L, 35);
        when(chatApi.listSinceMessageId(USER_ID, 50L))
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
        givenLockAcquired();
        // 没有历史 summary —— sinceMessageId 应回退到 0
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.empty());
        when(chatApi.countSinceMessageId(USER_ID, 0L))
                .thenReturn((long) MemoryConstants.SUMMARY_TRIGGER_THRESHOLD);
        List<MessageDto> allMessages = buildMessages(1L, MemoryConstants.SUMMARY_TRIGGER_THRESHOLD);
        when(chatApi.listSinceMessageId(USER_ID, 0L))
                .thenReturn(allMessages);
        when(summaryService.summarize(allMessages)).thenReturn("首次摘要");

        scheduler.onMessagePersisted(eventForMessage(30L));

        verify(chatApi).countSinceMessageId(USER_ID, 0L);
        verify(chatApi).listSinceMessageId(USER_ID, 0L);
        verify(summaryService).summarize(allMessages);
        ArgumentCaptor<MemorySummaryEntity> captor = ArgumentCaptor.forClass(MemorySummaryEntity.class);
        verify(summaryRepository).save(captor.capture());
        assertThat(captor.getValue().getPeriodStartMessageId()).isEqualTo(1L);
        assertThat(captor.getValue().getPeriodEndMessageId()).isEqualTo(30L);
    }

    @Test
    void onMessagePersisted_swallowsServiceException_doesNotThrow() {
        givenLockAcquired();
        // summarize 抛异常 —— listener 必须吞掉，不能影响主对话
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.of(summaryEndingAt(100L)));
        when(chatApi.countSinceMessageId(USER_ID, 100L))
                .thenReturn((long) MemoryConstants.SUMMARY_TRIGGER_THRESHOLD);
        List<MessageDto> newMessages = buildMessages(101L, MemoryConstants.SUMMARY_TRIGGER_THRESHOLD);
        when(chatApi.listSinceMessageId(USER_ID, 100L))
                .thenReturn(newMessages);
        when(summaryService.summarize(newMessages))
                .thenThrow(new RuntimeException("LLM 调用失败"));

        // 不应抛出
        scheduler.onMessagePersisted(eventForMessage(130L));

        verify(summaryRepository, never()).save(any(MemorySummaryEntity.class));
    }

    // -------- Plan 2.6 patch：并发锁 + DB 唯一约束 --------

    /**
     * Plan 2.6 第 1 层防御：并发抢锁失败的线程必须跳过整个评估流程。
     *
     * <p>场景：@Async + @TransactionalEventListener 在快速连续消息事件下可能并发
     * 派两个线程跑 onMessagePersisted。模拟第二线程拿锁失败 → 必须立刻返回，
     * 不调 LLM，不查 DB，不写 memory_summaries。
     */
    @Test
    void onMessagePersisted_concurrentEvaluation_secondCallSkipsWhenLockNotAcquired() {
        // 第一次调用拿到锁，第二次调用拿不到锁
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true)
                .thenReturn(false);
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.of(summaryEndingAt(100L)));
        when(chatApi.countSinceMessageId(USER_ID, 100L)).thenReturn(5L);

        // 第一次：正常评估（5 < 30 不触发 summarize，但走完整链路）
        scheduler.onMessagePersisted(eventForMessage(105L));
        // 第二次：抢锁失败 —— 必须直接返回，连 findFirst...Desc 都不调
        scheduler.onMessagePersisted(eventForMessage(106L));

        // findFirst 只调了 1 次（第二次在锁前就 return 了）
        verify(summaryRepository, times(1))
                .findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID);
        verify(summaryService, never()).summarize(anyList());
        verify(summaryRepository, never()).save(any(MemorySummaryEntity.class));
    }

    /**
     * Plan 2.6 第 1 层防御：评估完成后必须释放锁（finally 块）。
     *
     * <p>无论评估流程内部走的是「不到阈值跳过」还是「触发并保存」分支，
     * Redis 锁都必须释放——否则后续同 user/char 的事件会被错误地节流 30s。
     */
    @Test
    void onMessagePersisted_lockReleasedAfterEvaluation() {
        givenLockAcquired();
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.of(summaryEndingAt(100L)));
        when(chatApi.countSinceMessageId(USER_ID, 100L)).thenReturn(10L);

        scheduler.onMessagePersisted(eventForMessage(101L));

        // 锁 key 模式：sanyan:memory:summary:lock:{userId}:{characterId}
        String expectedLockKey = "sanyan:memory:summary:lock:" + USER_ID + ":" + CHARACTER_ID;
        verify(kvCache).delete(eq(expectedLockKey));
    }

    /**
     * Plan 2.6 第 3 层防御：DB 唯一约束抛 DataIntegrityViolationException 时优雅吞掉。
     *
     * <p>双保险：Redis 锁失效或被绕过时（重启 / Redis 故障 / 跨实例并发），
     * V9 migration 加的 UNIQUE (user_id, character_id, period_end_message_id) 兜底。
     * Repository.save 抛约束违例 → log.warn 但不重抛（后台任务失败不能影响主对话）。
     */
    @Test
    void onMessagePersisted_dbConstraintViolation_swallowedGracefully() {
        givenLockAcquired();
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.of(summaryEndingAt(100L)));
        when(chatApi.countSinceMessageId(USER_ID, 100L))
                .thenReturn((long) MemoryConstants.SUMMARY_TRIGGER_THRESHOLD);
        List<MessageDto> newMessages = buildMessages(101L, MemoryConstants.SUMMARY_TRIGGER_THRESHOLD);
        when(chatApi.listSinceMessageId(USER_ID, 100L))
                .thenReturn(newMessages);
        when(summaryService.summarize(newMessages)).thenReturn("正常生成的摘要");
        when(summaryRepository.save(any(MemorySummaryEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_memory_summaries_period_end 违例"));

        // 不应抛出（异常被外层 catch + 内层 catch 任一吞掉都可接受，关键是不向上抛）
        scheduler.onMessagePersisted(eventForMessage(130L));

        // save 确实被调用了一次（说明走到了写库分支，约束违例由 catch 吞掉）
        verify(summaryRepository, times(1)).save(any(MemorySummaryEntity.class));
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
    private static List<MessageDto> buildMessages(long startId, int count) {
        List<MessageDto> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new MessageDto(
                    startId + i,
                    USER_ID,
                    i % 2 == 0 ? SenderType.USER : SenderType.AI,
                    "msg-" + (startId + i),
                    null));
        }
        return list;
    }
}
