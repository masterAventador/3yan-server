package com.sanyan.chat;

/**
 * 消息发送者类型常量。
 *
 * <p>S3 Phase 5 起从 {@code com.sanyan.chat.internal.SenderType} 上提到 chat-api 顶层，
 * 因为跨模块都要识别 USER 还是 AI：
 * <ul>
 *   <li>memory-core 的 listener 按 role 过滤（只 USER 消息触发画像刷新）</li>
 *   <li>MemoryProfileRefreshService / MemoryChunkBuilder 拼 prompt 时区分人/AI</li>
 *   <li>{@link com.sanyan.chat.event.MessagePersistedEvent#role()} 的取值来源</li>
 * </ul>
 *
 * <p>注意：本类保持 String 常量形态（而非 enum）。原因：
 * <ul>
 *   <li>{@code MessageEntity#senderType} 是 {@code String} 列（JPA 直接映射）</li>
 *   <li>{@link com.sanyan.chat.event.MessagePersistedEvent#role()} 是 String</li>
 *   <li>调用方多走 {@code SenderType.USER.equalsIgnoreCase(role)} 字符串比较</li>
 * </ul>
 * 若未来要改 enum，需联动迁移 JPA 列类型 + 事件 record 字段类型 + 所有比较点，属独立重构任务。
 */
public final class SenderType {
    public static final String USER = "user";
    public static final String AI = "ai";

    private SenderType() {}
}
