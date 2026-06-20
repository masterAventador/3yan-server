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
     * 查某用户最近 N 条 AI 主动推送消息（is_proactive=true），按 id 降序（最新在前）。
     *
     * <p>对应 {@code MessageRepository#findByUserIdAndIsProactiveTrueOrderByIdDesc(userId, PageRequest.of(0, limit))}。
     * 供主动推送生成时做"反重复"——把最近推过的内容喂回 prompt，让 LLM 别复读同义的早晚安/想你话题。
     *
     * @param limit 上限条数（必须 &gt; 0）
     */
    List<MessageDto> listRecentProactive(Long userId, int limit);

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

    /**
     * 投递一条主动消息（proactive 域委托入口）：把每条 segment 落库为 ai message，
     * 经 DeliveryService 走 4 层投递（在线 WS / ACK 兜底 / 离线推送）。
     *
     * <p>跨域边界（spec §7.2）：proactive-core 不能依赖 chat-core，故投递能力经本契约暴露。
     *
     * @return 落库的 ai message id 列表（与 segments 一一对应、顺序一致）
     */
    List<Long> deliverProactiveMessage(Long userId, Long characterId, List<String> segments);

    /** 该用户自最后一条消息以来未回应的主动推送条数。供主动推送互动退避降频。 */
    long countUnansweredProactive(Long userId);
}
