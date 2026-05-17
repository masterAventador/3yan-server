package com.sanyan.chat.event;

/**
 * Plan 2 Task N3：消息持久化领域事件。
 *
 * <p>{@link com.sanyan.chat.internal.MessageService} 在消息成功保存后通过
 * {@link org.springframework.context.ApplicationEventPublisher} 发布此事件。
 * 订阅方走 {@code @TransactionalEventListener(phase = AFTER_COMMIT)} +
 * {@code @Async}，确保"事件发出但事务回滚"的状态不一致不会发生。
 *
 * <p>当前订阅方（Plan 2 P 阶段陆续接入）：
 * <ul>
 *   <li>{@code SummaryScheduler}（N3）：累积 30 条新消息触发摘要</li>
 *   <li>{@code UserMessageProfileRefreshListener}（Plan 2.5）：每条用户消息触发画像刷新</li>
 *   <li>{@code MessageEmbeddingIndexListener}（P5）：累积 chunk 后送 RAG 索引队列</li>
 * </ul>
 *
 * <p>字段约定：
 * <ul>
 *   <li>{@code messageId}：本次保存的消息 id（已回填）</li>
 *   <li>{@code userId}：消息所属用户 id</li>
 *   <li>{@code characterId}：消息所属角色 id（MVP 阶段固定为 1L = 林小婉，预留多角色扩展）</li>
 *   <li>{@code role}：发送者角色，取值见 {@link com.sanyan.chat.internal.SenderType}
 *       ("user" / "ai")，订阅方靠这个字段过滤（如档案抽取只关心 USER）</li>
 * </ul>
 */
public record MessagePersistedEvent(
        Long messageId,
        Long userId,
        Long characterId,
        String role) {
}
