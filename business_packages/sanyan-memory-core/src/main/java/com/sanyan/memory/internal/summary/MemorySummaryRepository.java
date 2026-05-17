package com.sanyan.memory.internal.summary;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 滚动摘要 Repository。
 *
 * <p>关键查询：{@link #findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc}——
 * 取"某用户 × 某角色"的最新一条摘要，用于：
 * <ul>
 *   <li>SummaryScheduler 判断"自上次摘要以来"的消息区间起点（拿 period_end_message_id）</li>
 *   <li>MemoryContextBuilder 取最近摘要拼进 LLM 上下文</li>
 * </ul>
 *
 * <p>本查询命中 V5 schema 的复合索引 {@code idx_memory_summaries_user_char_created
 * (user_id, character_id, created_at DESC)}，order by + limit 1 直接走索引扫一行。
 *
 * <p>"自上次摘要以来的新增消息数"由消息表统计（不在本 Repository 上），N3 task 决定具体方法位置。
 */
public interface MemorySummaryRepository extends JpaRepository<MemorySummaryEntity, Long> {

    Optional<MemorySummaryEntity> findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(
            Long userId, Long characterId);
}
