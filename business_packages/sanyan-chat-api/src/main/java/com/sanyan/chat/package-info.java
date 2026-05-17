/**
 * sanyan-chat-api：Chat 域对内契约（java-backend §2.2）。
 *
 * <p>含 {@link com.sanyan.chat.ChatApi} 接口 + {@link com.sanyan.chat.dto.MessageDto}
 * + {@link com.sanyan.chat.SenderType} 常量类
 * + {@link com.sanyan.chat.event.MessagePersistedEvent} record。零依赖。
 *
 * <p>实现在 sanyan-chat-core，通过 {@code ChatApiImpl} 暴露（Phase 5 Task 5.2 创建）。
 */
package com.sanyan.chat;
