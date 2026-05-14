package com.sanyan.memory.dto;

/**
 * 长期记忆「整合上下文」DTO（Plan 2 Task Q1）。
 *
 * <p>由 {@code MemoryContextBuilder} 组合「结构化档案 + 滚动摘要 + RAG 检索片段」三层
 * 长期记忆输出的最终文本，下游 {@code PromptBuilder}（Q3）把它作为一条 system message
 * 注入到主对话 prompt 的"她对你的记忆"段。
 *
 * <p>放在 -api 模块的原因：跨模块 DTO 契约。{@code sanyan-memory-core} 负责编排实现，
 * 调用方（{@code sanyan-business} 下的 chat / AiService 层）只依赖 -api 拿到结果即可，
 * 不需要触及 -core 实现细节。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code text}：已经拼好的纯文本上下文，调用方直接作为 system 消息内容塞进 LLM。
 *       三段（profile / summary / RAG）由 builder 内部按固定顺序拼接，调用方不需要再加工。</li>
 * </ul>
 *
 * <p>"无 context"语义约定：当 builder 发现三层数据全空时返回 {@code null}（不是 EMPTY 实例），
 * 让调用方用 {@code if (ctx == null) skip} 直接跳过 system 消息的注入。{@link #EMPTY} 仅作
 * 测试 / 占位用途，业务路径上不会被返回。
 */
public record MemoryContext(String text) {

    /** 空上下文占位实例，仅测试 / 占位使用；运行时 builder 在空数据时返回 null。 */
    public static final MemoryContext EMPTY = new MemoryContext("");

    /** 文本为 null / 空白时视为"无内容"，调用方可用本方法决定是否注入。 */
    public boolean isEmpty() {
        return text == null || text.isBlank();
    }
}
