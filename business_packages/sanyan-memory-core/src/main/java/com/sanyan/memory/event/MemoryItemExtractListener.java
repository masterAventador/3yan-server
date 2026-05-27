package com.sanyan.memory.event;

import com.sanyan.chat.ChatApi;
import com.sanyan.chat.SenderType;
import com.sanyan.chat.dto.MessageDto;
import com.sanyan.chat.event.MessagePersistedEvent;
import com.sanyan.memory.internal.item.MemoryItemExtractService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Plan 4：用户消息触发结构化记忆「实时抽取」（spec §4.2）。
 *
 * <p>监听 chat 域 {@link MessagePersistedEvent}，与 {@code UserMessageProfileRefreshListener}
 * 并行（两条 listener 各管一条记忆通道：画像 / 结构化条目）。
 *
 * <p>关键约束（照 profile 刷新 listener）：
 * <ul>
 *   <li>仅 {@code role=user}（AI 回复不触发）</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)}：等消息事务提交后再抽，避免回滚不一致</li>
 *   <li>{@code @Async}：LLM 调用数秒，异步不阻塞主对话</li>
 *   <li>异常吞掉 + log.error：后台任务失败决不能影响主对话</li>
 * </ul>
 *
 * <p>与 profile 刷新不同——本 listener<b>不加 KvCache 节流</b>：抽取靠 LLM 逐条增量判定去重
 * （spec §4.3），关键信息常在"临别最后一句"，每条用户消息都要抽，不能节流跳过。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MemoryItemExtractListener {

    private final ChatApi chatApi;
    private final MemoryItemExtractService extractService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessagePersisted(MessagePersistedEvent event) {
        if (!SenderType.USER.equalsIgnoreCase(event.role())) {
            return;
        }
        try {
            // ChatApi 按 id 降序返回，第 0 条即本次持久化的最新用户消息
            List<MessageDto> recent = chatApi.listRecentByUser(event.userId(), 1);
            if (recent == null || recent.isEmpty()) {
                return;
            }
            String latest = recent.get(0).content();
            extractService.extract(event.userId(), event.characterId(), latest, event.messageId());
            log.info("记忆条目抽取完成 user={} char={} msg={}",
                    event.userId(), event.characterId(), event.messageId());
        } catch (Exception e) {
            // 后台任务失败不应影响主对话——吞掉 + ERROR 日志便于线上排查
            log.error("记忆条目抽取失败 user={} char={} msg={}",
                    event.userId(), event.characterId(), event.messageId(), e);
        }
    }
}
