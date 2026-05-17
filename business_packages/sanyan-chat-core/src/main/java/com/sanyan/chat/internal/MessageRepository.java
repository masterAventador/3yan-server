package com.sanyan.chat.internal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {
    List<MessageEntity> findByUserIdAndIdGreaterThanOrderByIdAsc(Long userId, Long afterId, Pageable pageable);
    List<MessageEntity> findByUserIdAndIdLessThanOrderByIdDesc(Long userId, Long beforeId, Pageable pageable);
    List<MessageEntity> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    /**
     * Plan 2 Task N3：统计某用户自指定 messageId 之后的消息条数。
     *
     * <p>SummaryScheduler 用此查询判断"自上次摘要以来"的新消息累积量是否达到
     * {@link com.sanyan.memory.MemoryConstants#SUMMARY_TRIGGER_THRESHOLD}。
     *
     * <p>注意：MVP 单角色阶段 MessageEntity 没有 character_id 列（系统硬编码 character=1L），
     * 所以查询不带 character 过滤；Plan 3 拆多角色后再加。
     */
    long countByUserIdAndIdGreaterThan(Long userId, Long sinceMessageId);

    /**
     * Plan 2 Task N3：拉取某用户自指定 messageId 之后的全部消息（按 id 升序，不分页）。
     *
     * <p>用于 SummaryScheduler 取摘要区间消息送给 MemorySummaryService。
     * 不带分页是因为触发摘要时区间大小固定在 {@code SUMMARY_TRIGGER_THRESHOLD}(=30) 量级，
     * 一次性取走更直接。与同名分页版本 {@link #findByUserIdAndIdGreaterThanOrderByIdAsc(Long, Long, Pageable)}
     * 按参数数量区分。
     */
    List<MessageEntity> findByUserIdAndIdGreaterThanOrderByIdAsc(Long userId, Long sinceMessageId);
}
