package com.sanyan.chat.dto;

import java.time.LocalDateTime;

/**
 * Message 跨模块 DTO。memory-core 等业务方通过 {@link com.sanyan.chat.ChatApi}
 * 拿这种结构而不是 {@code MessageEntity}。
 *
 * <p>字段对应 {@code com.sanyan.chat.internal.MessageEntity}（chat-core internal），
 * 暴露最少必要集合，不暴露 JPA 注解 / 内部审计字段。
 *
 * <p>字段类型说明：
 * <ul>
 *   <li>{@code senderType}：保持 {@link String}（不引 {@link com.sanyan.chat.SenderType}
 *       的常量类做形参类型限制），因为底层 {@code MessageEntity#senderType} 是 String 列，
 *       且 memory-core 现有调用都是 String 比较（{@code SenderType.USER.equalsIgnoreCase(...)}）</li>
 *   <li>{@code createdAt}：用 {@link LocalDateTime}（与 Entity 一致，避免时区转换歧义）</li>
 * </ul>
 *
 * <p>MVP 单角色约束：当前 {@code MessageEntity} 没有 character_id 列，故本 DTO 也没有
 * characterId 字段；多角色拆分后再加（Plan 3）。
 */
public record MessageDto(
        Long id,
        Long userId,
        String senderType,
        String content,
        LocalDateTime createdAt) {}
