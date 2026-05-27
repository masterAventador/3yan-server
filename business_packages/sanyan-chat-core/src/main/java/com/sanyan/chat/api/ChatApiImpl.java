package com.sanyan.chat.api;

import com.sanyan.chat.ChatApi;
import com.sanyan.chat.dto.MessageDto;
import com.sanyan.chat.internal.DeliveryService;
import com.sanyan.chat.internal.MessageDtoMapper;
import com.sanyan.chat.internal.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link ChatApi} 的实现 —— 薄包装层，把跨模块查询委托给 {@link MessageRepository}
 * 然后通过 {@link MessageDtoMapper} 把 entity 转成 {@link MessageDto}。
 *
 * <p>S3 Phase 5 新建：取代 memory-core 之前直接 import {@code MessageEntity / MessageRepository}
 * 的违规跨模块依赖。
 *
 * <p>注意：本类不应承载业务逻辑（按 java-backend rule §3.4 「api/ 通常薄，委托给 internal」）。
 * 复杂的过滤 / 拼装应该走 Service。当前 4 个方法都是单条 SQL + 一次映射，足够薄。
 */
@Service
@RequiredArgsConstructor
public class ChatApiImpl implements ChatApi {

    private final MessageRepository repository;
    private final DeliveryService deliveryService;

    @Override
    public List<MessageDto> findAllByIds(List<Long> messageIds) {
        return MessageDtoMapper.toDtos(repository.findAllById(messageIds));
    }

    @Override
    public List<MessageDto> listRecentByUser(Long userId, int limit) {
        return MessageDtoMapper.toDtos(
                repository.findByUserIdOrderByIdDesc(userId, PageRequest.of(0, limit)));
    }

    @Override
    public long countSinceMessageId(Long userId, Long sinceMessageId) {
        return repository.countByUserIdAndIdGreaterThan(userId, sinceMessageId);
    }

    @Override
    public List<MessageDto> listSinceMessageId(Long userId, Long sinceMessageId) {
        return MessageDtoMapper.toDtos(
                repository.findByUserIdAndIdGreaterThanOrderByIdAsc(userId, sinceMessageId));
    }

    @Override
    public List<Long> deliverProactiveMessage(Long userId, Long characterId, List<String> segments) {
        return deliveryService.deliver(userId, characterId, segments);
    }
}
