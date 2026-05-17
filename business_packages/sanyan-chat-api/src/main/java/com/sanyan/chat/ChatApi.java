package com.sanyan.chat;

import com.sanyan.chat.dto.MessageDto;

import java.util.List;

/**
 * Chat 域对内 API 契约。memory-core 等业务方通过本接口拿消息数据。
 *
 * <p>历史：S3 Phase 5 之前 memory-core 直接 import {@code MessageEntity} / {@code MessageRepository}
 * （违反 java-backend §3.3 跨模块不引 internal）。Phase 5 拆 chat 域后，所有跨模块查询走本接口。
 *
 * <p>方法清单覆盖 memory-core 已有调用点：
 * <ul>
 *   <li>{@link #findAllByIds(List)} — {@code MessageEmbeddingIndexListener} 批量回查消息</li>
 *   <li>{@link #listRecentByUser(Long, int)} — {@code UserMessageProfileRefreshListener} 拉最近 N 条</li>
 *   <li>{@link #countSinceMessageId(Long, Long)} — {@code SummaryScheduler} 判断"上次摘要以来"累积量</li>
 *   <li>{@link #listSinceMessageId(Long, Long)} — {@code SummaryScheduler} 拉摘要区间消息</li>
 * </ul>
 *
 * <p>MVP 单角色约束：当前所有查询不带 characterId 过滤（{@code MessageEntity} 没有该列）。
 * Plan 3 拆多角色后，按需补 characterId 参数。
 */
public interface ChatApi {

    /**
     * 按 id 批量查消息，返回结果不保证顺序，调用方按需排序。
     *
     * <p>对应 {@code MessageRepository#findAllById}。{@code MessageEmbeddingIndexListener}
     * 用此查询批量回查消息后按 id 升序排，喂给 RAG chunk builder。
     */
    List<MessageDto> findAllByIds(List<Long> messageIds);

    /**
     * 查某用户最近 N 条消息，按 id 降序（最新在前）。
     *
     * <p>对应 {@code MessageRepository#findByUserIdOrderByIdDesc(userId, PageRequest.of(0, limit))}。
     *
     * @param limit 上限条数（必须 &gt; 0）；调用方对长上下文应按 token 估算切分
     */
    List<MessageDto> listRecentByUser(Long userId, int limit);

    /**
     * 统计某用户自指定 messageId 之后的消息条数（不含 sinceMessageId 本身）。
     *
     * <p>对应 {@code MessageRepository#countByUserIdAndIdGreaterThan}。
     * {@code SummaryScheduler} 用此判断"自上次摘要以来"是否累积到摘要触发阈值。
     */
    long countSinceMessageId(Long userId, Long sinceMessageId);

    /**
     * 拉某用户自指定 messageId 之后的全部消息（按 id 升序，不分页）。
     *
     * <p>对应 {@code MessageRepository#findByUserIdAndIdGreaterThanOrderByIdAsc(userId, sinceId)}
     * 的"无 Pageable"重载。不分页是因为触发摘要时区间大小固定在阈值量级（约 30 条），
     * 一次性取走更直接。
     */
    List<MessageDto> listSinceMessageId(Long userId, Long sinceMessageId);
}
