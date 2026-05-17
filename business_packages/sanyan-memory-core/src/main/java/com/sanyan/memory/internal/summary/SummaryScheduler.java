package com.sanyan.memory.internal.summary;

import com.sanyan.chat.event.MessagePersistedEvent;
import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.MessageRepository;
import com.sanyan.common.cache.KvCache;
import com.sanyan.memory.MemoryConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Plan 2 Task N3：滚动摘要触发器。
 *
 * <p>监听 {@link MessagePersistedEvent}（每条消息持久化后由
 * {@link com.sanyan.chat.internal.MessageService} 发布）。统计某用户自上次摘要以来的新消息数，
 * 达到 {@link MemoryConstants#SUMMARY_TRIGGER_THRESHOLD}（= 30）就调
 * {@link MemorySummaryService#summarize} 生成一段摘要，并写入 memory_summaries 表。
 *
 * <p>关键时机约束：
 * <ul>
 *   <li>{@code @TransactionalEventListener(phase = AFTER_COMMIT)}：必须等
 *       MessageService 的消息事务提交后再触发，避免事务回滚但摘要任务已启动的不一致</li>
 *   <li>{@code @Async}：摘要走 LLM 调用，可能耗时数秒，必须异步避免阻塞主对话回复链路</li>
 * </ul>
 *
 * <p>异常吞掉策略：catch + log.error，不向上抛——后台摘要失败决不能影响主聊天体验。
 * 失败一次下次还会触发（每条新消息都会评估一次阈值）。
 *
 * <p>MVP 单角色约束：当前 {@link MessageEntity} 没有 character_id 列，所以 messageRepository
 * 的查询只按 userId 过滤；character 维度仅在 MemorySummaryEntity 上区分。Plan 3 拆多角色
 * 后 MessageEntity 加 character_id 列，本类查询同步加 character 过滤。
 *
 * <p><b>Plan 2.6 patch：并发双跑修复（三层防御）</b>
 * <ul>
 *   <li>第 1 层 Redis SETNX 锁：@Async + @TransactionalEventListener 在快速连续消息事件下
 *       可能并发派两个线程，都看到 latest summary 为空就重复跑评估 + LLM 调用 + 写库。
 *       开头 30s TTL 的 SETNX 抢锁，抢不到直接 log.info 跳过</li>
 *   <li>第 2 层 DB UNIQUE 约束（V9 migration）：跨实例 / Redis 故障时兜底。
 *       约束 (user_id, character_id, period_end_message_id) NULLS NOT DISTINCT</li>
 *   <li>第 3 层 catch {@link DataIntegrityViolationException}：约束抛违例时优雅吞掉，
 *       不向外抛（同主对话异常吞掉原则）</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SummaryScheduler {

    /** Redis 锁 key 前缀：{prefix}{userId}:{characterId}。Plan 2.6 patch。 */
    static final String LOCK_KEY_PREFIX = "sanyan:memory:summary:lock:";

    /**
     * 评估锁 TTL：30s。
     *
     * <p>覆盖 LLM 摘要调用的最长合理时长（实测 5-10s）+ 网络抖动余量。
     * 即使持锁线程崩溃也会自动过期，下条消息事件会重新拿锁评估。
     */
    static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private final MemorySummaryRepository summaryRepository;
    private final MemorySummaryService summaryService;
    private final MessageRepository messageRepository;
    private final KvCache kvCache;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessagePersisted(MessagePersistedEvent event) {
        // Plan 2.6 patch 第 1 层：Redis SETNX 锁抢占
        String lockKey = LOCK_KEY_PREFIX + event.userId() + ":" + event.characterId();
        boolean acquired = kvCache.setIfAbsent(lockKey, "1", LOCK_TTL);
        if (!acquired) {
            log.info("Summary 评估并发跳过 user={} char={}", event.userId(), event.characterId());
            return;
        }

        try {
            // 1) 找最新 summary 的 period_end_message_id，没有就回退到 0（= 全部历史消息）
            Optional<MemorySummaryEntity> latest = summaryRepository
                    .findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(event.userId(), event.characterId());
            Long sinceMessageId = latest
                    .map(MemorySummaryEntity::getPeriodEndMessageId)
                    .orElse(0L);

            // 2) 统计自上次摘要以来的新消息数；不到阈值直接返回
            long newCount = messageRepository.countByUserIdAndIdGreaterThan(event.userId(), sinceMessageId);
            if (newCount < MemoryConstants.SUMMARY_TRIGGER_THRESHOLD) {
                return;
            }

            // 3) 取这批新消息送给 service 生成摘要文本
            List<MessageEntity> newMessages = messageRepository
                    .findByUserIdAndIdGreaterThanOrderByIdAsc(event.userId(), sinceMessageId);
            if (newMessages.isEmpty()) {
                // 防御：count 和 find 之间消息被删（理论上不会，记一条 warn 跳过）
                log.warn("Summary 触发但取消息为空: user={} since={}", event.userId(), sinceMessageId);
                return;
            }
            String summaryText = summaryService.summarize(newMessages);

            // 4) 保存新 MemorySummaryEntity
            MemorySummaryEntity entity = new MemorySummaryEntity();
            entity.setUserId(event.userId());
            entity.setCharacterId(event.characterId());
            entity.setPeriodStartMessageId(newMessages.get(0).getId());
            entity.setPeriodEndMessageId(newMessages.get(newMessages.size() - 1).getId());
            entity.setSummaryText(summaryText);
            entity.setMessageCount(newMessages.size());

            try {
                summaryRepository.save(entity);
                log.info("Summary 落库: user={} char={} msgCount={} period=[{}, {}]",
                        event.userId(), event.characterId(), newMessages.size(),
                        entity.getPeriodStartMessageId(), entity.getPeriodEndMessageId());
            } catch (DataIntegrityViolationException e) {
                // Plan 2.6 patch 第 3 层：DB 唯一约束兜底拦截重复落库
                log.warn("Summary 重复落库被 DB 约束拦截 user={} char={} period_end={}",
                        event.userId(), event.characterId(), entity.getPeriodEndMessageId());
            }
        } catch (Exception e) {
            // 后台任务失败不应影响主对话——吞掉并打 ERROR 日志便于线上排查
            log.error("Summary 生成失败 (event={})", event, e);
        } finally {
            // Plan 2.6 patch 第 1 层：评估完成后释放锁，让下一轮事件能继续评估
            kvCache.delete(lockKey);
        }
    }
}
