package com.sanyan.memory.event;

import com.sanyan.chat.event.MessagePersistedEvent;
import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.MessageRepository;
import com.sanyan.chat.internal.SenderType;
import com.sanyan.common.cache.KvCache;
import com.sanyan.memory.MemoryConstants;
import com.sanyan.memory.internal.profile.MemoryProfileExtractService;
import com.sanyan.memory.internal.profile.MemoryProfileMergeService;
import com.sanyan.memory.internal.profile.dto.ProfileExtraction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plan 2 Task O4：用户消息触发档案抽取链路。
 *
 * <p>监听 {@link MessagePersistedEvent}（{@code MessageService} 发布）。处理流程：
 * <ol>
 *   <li>只处理 {@code role=user} 事件（AI 回复不触发档案抽取）</li>
 *   <li>用 Redis SETNX 节流：同一 {@code (userId, characterId)} 在
 *       {@link MemoryConstants#PROFILE_EXTRACT_THROTTLE_MINUTES} 分钟内最多触发一次</li>
 *   <li>取该 user 最近 {@link #RECENT_MESSAGES_FOR_EXTRACT} 条消息，反转为时间正序</li>
 *   <li>调 {@link MemoryProfileExtractService#extract} 让 LLM 抽结构化档案</li>
 *   <li>抽取返回非 null 时调 {@link MemoryProfileMergeService#merge} 合并到 profile</li>
 * </ol>
 *
 * <p>关键时机约束（与 N3 SummaryScheduler 一致）：
 * <ul>
 *   <li>{@code @TransactionalEventListener(phase = AFTER_COMMIT)}：必须等消息事务提交后再触发，
 *       避免事务回滚但抽取任务已启动的不一致</li>
 *   <li>{@code @Async}：LLM 调用可能数秒，异步避免阻塞主对话 SSE</li>
 * </ul>
 *
 * <p><b>异常吞掉策略</b>：catch + log.error 不向上抛——后台任务失败决不能影响主对话体验。
 * 失败一次下次还会触发（节流 key 5 分钟后过期）。
 *
 * <p><b>为什么走 KvCache 不直接用 RedisTemplate</b>：foundation-layer 规则约束业务代码必须走
 * common-cache 封装，统一 key 管理 + 后续可加监控埋点。
 *
 * <p><b>MVP 单角色约束</b>：当前 {@link MessageEntity} 没有 character_id 列，
 * {@code findByUserIdOrderByIdDesc} 只按 userId 过滤，character 维度仅在节流 key 和 profile 表上区分。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserMessageProfileExtractListener {

    /** Redis 节流 key 前缀：{prefix}{userId}:{characterId}。 */
    static final String THROTTLE_KEY_PREFIX = "sanyan:memory:profile:throttle:";

    /**
     * 每次触发抽取时送给 LLM 的最近消息条数。
     *
     * <p>10 条够覆盖一个完整对话回合（用户问 + AI 答交替几轮），又不至于让 LLM 上下文太长。
     * 上游 ExtractService 不做额外裁剪，只关心整体语义信号。
     */
    static final int RECENT_MESSAGES_FOR_EXTRACT = 10;

    private final MemoryProfileExtractService extractService;
    private final MemoryProfileMergeService mergeService;
    private final MessageRepository messageRepository;
    private final KvCache kvCache;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessagePersisted(MessagePersistedEvent event) {
        // 1) 仅 user 消息触发；AI 回复直接跳过
        if (!SenderType.USER.equalsIgnoreCase(event.role())) {
            return;
        }

        // 2) Redis 节流：同一 user/char 5 分钟内仅触发一次
        String throttleKey = THROTTLE_KEY_PREFIX + event.userId() + ":" + event.characterId();
        boolean acquired = kvCache.setIfAbsent(
                throttleKey,
                "1",
                Duration.ofMinutes(MemoryConstants.PROFILE_EXTRACT_THROTTLE_MINUTES));
        if (!acquired) {
            log.info("Profile 抽取节流命中（{} 分钟内已触发）: user={} char={}",
                    MemoryConstants.PROFILE_EXTRACT_THROTTLE_MINUTES,
                    event.userId(), event.characterId());
            return;
        }

        try {
            // 3) 取最近 N 条消息（Repository 按 id 降序返回；这里反转为时间正序送给 LLM）
            List<MessageEntity> recentDesc = messageRepository.findByUserIdOrderByIdDesc(
                    event.userId(), PageRequest.of(0, RECENT_MESSAGES_FOR_EXTRACT));
            List<MessageEntity> recentAsc = new ArrayList<>(recentDesc);
            Collections.reverse(recentAsc);

            // 4) LLM 抽取
            ProfileExtraction extraction = extractService.extract(recentAsc);
            if (extraction == null) {
                log.debug("Profile 抽取返回 null（LLM 失败或无可抽内容）user={} char={}",
                        event.userId(), event.characterId());
                return;
            }

            // 5) 合并到 profile
            mergeService.merge(event.userId(), event.characterId(), extraction);
            log.info("Profile 已更新 user={} char={}", event.userId(), event.characterId());
        } catch (Exception e) {
            // 后台任务失败不应影响主对话——吞掉 + ERROR 日志便于线上排查
            log.error("Profile 抽取/合并失败 user={} char={}",
                    event.userId(), event.characterId(), e);
        }
    }
}
