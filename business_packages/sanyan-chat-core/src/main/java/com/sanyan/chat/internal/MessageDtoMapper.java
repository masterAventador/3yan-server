package com.sanyan.chat.internal;

import com.sanyan.chat.dto.MessageDto;

import java.util.List;

/**
 * {@link MessageEntity} → {@link MessageDto} 映射工具（chat-core 内私有，跨模块只能通过
 * {@link com.sanyan.chat.ChatApi} 拿到 {@link MessageDto}）。
 *
 * <p>按全局 CLAUDE.md 「static 优先原则」：无任何实例状态，只是把字段值搬到 record 上，
 * 以 {@code final class + private 构造 + static 方法} 形态阻止实例化。
 *
 * <p>访问修饰符 public —— ChatApiImpl 在兄弟包 {@code com.sanyan.chat.api} 下，
 * Java package private 不能跨包访问，所以提升为 public；但本类位于 {@code internal/} 下，
 * 跨模块仍不可见（其他业务模块只通过 {@link com.sanyan.chat.ChatApi} 拿 DTO）。
 */
public final class MessageDtoMapper {

    private MessageDtoMapper() {}

    public static MessageDto toDto(MessageEntity e) {
        if (e == null) {
            return null;
        }
        return new MessageDto(
                e.getId(),
                e.getUserId(),
                e.getSenderType(),
                e.getContent(),
                e.getCreatedAt());
    }

    /**
     * 注：返回的是 {@code stream().toList()} 不可变 list。调用方若需要 sort / 增删，
     * 请先 {@code new ArrayList<>(...)} 拷一份。
     */
    public static List<MessageDto> toDtos(List<MessageEntity> entities) {
        if (entities == null) {
            return List.of();
        }
        return entities.stream().map(MessageDtoMapper::toDto).toList();
    }
}
