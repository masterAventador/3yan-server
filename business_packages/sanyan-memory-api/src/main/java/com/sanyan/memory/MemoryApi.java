package com.sanyan.memory;

import com.sanyan.memory.dto.MemoryContext;
import com.sanyan.memory.dto.MemoryItemDto;

/**
 * Plan 2 Task Q2：长期记忆模块对内契约。
 *
 * <p>定位：跨业务模块的"长期记忆查询入口"。chat 链路（{@code sanyan-business} 下的
 * AiService / PromptBuilder）在生成回复前调本接口拿到「她对你的记忆」整合上下文，
 * 然后塞进 system prompt。
 *
 * <p>放在 -api 模块：调用方只依赖 {@code sanyan-memory-api}（接口 + DTO 契约），
 * 实现（{@code MemoryApiImpl} + 三层数据源）封装在 {@code sanyan-memory-core}，
 * 调用方不需要、也不允许触及 -core 实现细节。
 *
 * <p>领域职责（**单一主接口**，符合 java-backend rule §2.2：一个 -api 只放一个 {@code <Domain>Api}）：
 * 只暴露"查询长期记忆 context"这一动作。其他能力（写入摘要 / 抽档案 / 建索引）由 -core 内部
 * 事件驱动（{@code MessagePersistedEvent} 触发 listener），不走本接口。
 *
 * @see com.sanyan.memory.dto.MemoryContext MemoryContext —— 整合后的纯文本上下文 DTO
 */
public interface MemoryApi {

    /**
     * 查询当前用户对当前角色的「长期记忆整合 context」。
     *
     * <p>三层来源（由 -core 的 {@code MemoryContextBuilder} 编排）：
     * <ol>
     *   <li><b>自然语言画像</b>（{@code memory_profiles.summary_text}）—— "她记得的关于你的事"</li>
     *   <li><b>滚动摘要</b>（{@code memory_summaries} 最新一段）—— "最近的对话纪要"</li>
     *   <li><b>RAG 检索</b>（{@code chat_embeddings} 语义召回 Top K）—— "相关历史片段"</li>
     * </ol>
     * 三段拼成单一文本返回，调用方直接当作 system message content 注入即可。
     *
     * <p><b>空 context 语义</b>：三层皆无可用数据时返回 {@code null}（不是 EMPTY 实例）。
     * 调用方约定 {@code if (ctx == null) skip system message injection}，避免污染 prompt。
     *
     * <p><b>降级保证</b>：embedding 远程服务挂掉时 RAG 段在 -core 内部降级为空（已打 ERROR 日志），
     * 本接口仍能用 profile + summary 返回非空 context，主对话链路不受影响。
     *
     * @param userId             用户 id（必填）
     * @param characterId        角色 id（必填，同一用户对不同角色的长期记忆相互隔离）
     * @param currentUserMessage 当前用户消息，作为 RAG 段语义检索的 query 文本；profile / summary 段不使用
     * @return 整合后的 {@link MemoryContext}；三层皆空时返回 {@code null}
     */
    MemoryContext getRelevantContext(Long userId, Long characterId, String currentUserMessage);

    /**
     * 按 id 查结构化记忆条目（Plan 4）。供 proactive 生成器拿 content 拼文案。
     *
     * @param itemId 条目 id
     * @return 条目 DTO
     * @throws com.sanyan.common.error.BusinessException 条目不存在时抛 MEMORY_ITEM_NOT_FOUND
     */
    MemoryItemDto getMemoryItem(Long itemId);

    /**
     * 标记条目已被主动消息消费（status=DONE）。主动消息发送成功后由 proactive 调用。
     *
     * @param itemId 条目 id
     */
    void markMemoryItemDone(Long itemId);
}
