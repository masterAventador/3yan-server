package com.sanyan.llm.internal;

/**
 * LLM 任务类型——用于按"任务性质"路由到不同的 Provider。
 *
 * <p>Plan 2 引入两种任务类型：
 * <ul>
 *   <li>{@link #USER_FACING}：用户直接看到回复的对话，要求**高质量 + 拟人化**。当前走豆包 doubao-seed-character。</li>
 *   <li>{@link #BACKGROUND}：后台异步任务（摘要 / 档案抽取 / 其他自动化），用户看不到原始输出，要求**低成本 + 大吞吐**。当前走 DeepSeek V4-Flash。</li>
 * </ul>
 */
public enum LLMTaskType {
    USER_FACING,
    BACKGROUND,
}
