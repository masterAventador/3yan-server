package com.sanyan.memory;

/**
 * Memory 模块关键常量集中位置（跨模块共享契约）。
 *
 * <p>对齐铁律: {@code SHORT_TERM_WINDOW_SIZE} 必须严格 &gt; {@code SUMMARY_TRIGGER_THRESHOLD}，
 * 防止"夹缝消息"（短期窗口够不到、摘要又因为异步落库延迟还没覆盖到的消息）。详见
 * Plan 2 spec「关键常量与对齐约束」段。
 *
 * <p>所有引用方（{@code AiService} / {@code SummaryScheduler} / RAG 相关 service）必须从本类取值，
 * 禁止任何地方硬编码字面量（遵守全局 CLAUDE.md「代码复用原则 - 值复用」）。
 *
 * <p>位置约束（Q3 task 调整）：
 * 本类放在 {@code sanyan-memory-api} 模块。原因是 Q3 task 让 {@code sanyan-business}
 * 的 {@code AiService} 也要用 {@link #SHORT_TERM_WINDOW_SIZE}——而 {@code sanyan-business}
 * 不能依赖 {@code sanyan-memory-core}（后者已反向依赖前者，会形成循环依赖）。
 * 常量本身是无 Spring 依赖的纯不变量，放在 -api 模块（契约层）是最合理的位置。
 */
public final class MemoryConstants {

    /** 调 AI 时拼到 prompt 里的短期上下文条数。必须严格 &gt; {@link #SUMMARY_TRIGGER_THRESHOLD}。 */
    public static final int SHORT_TERM_WINDOW_SIZE = 32;

    /** 每累积 N 条新消息触发一次后台摘要。短期窗口比这个值大 2 条，给异步落库留 buffer。 */
    public static final int SUMMARY_TRIGGER_THRESHOLD = 30;

    /** 每段 RAG chunk 的最小消息数。 */
    public static final int RAG_CHUNK_MIN_SIZE = 5;

    /** 每段 RAG chunk 的最大消息数。 */
    public static final int RAG_CHUNK_MAX_SIZE = 10;

    /** 单个 chunk 的最大 token 上限，超过强制切段。 */
    public static final int RAG_CHUNK_MAX_TOKEN = 400;

    /** RAG 检索 Top K。 */
    public static final int RAG_TOP_K = 5;

    /** RAG 检索的最低余弦相似度阈值。低于此值的片段不进 prompt。 */
    public static final double RAG_MIN_COS_SIM = 0.6;

    /** 同一用户的档案抽取节流分钟数，Redis SETNX TTL 用。 */
    public static final int PROFILE_EXTRACT_THROTTLE_MINUTES = 5;

    /** 远程 embedding server TCP 连接超时（毫秒）。 */
    public static final int EMBEDDING_HTTP_CONNECT_TIMEOUT_MS = 3000;

    /** 远程 embedding server 读响应超时（毫秒）。必须 &gt; CONNECT 超时。 */
    public static final int EMBEDDING_HTTP_READ_TIMEOUT_MS = 10000;

    /** 远程 embedding server 网络异常最大重试次数（4xx 不重试）。 */
    public static final int EMBEDDING_HTTP_MAX_RETRIES = 2;

    /** 远程 embedding server 重试间隔基数（毫秒），用于指数退避。 */
    public static final long EMBEDDING_HTTP_RETRY_BACKOFF_MS = 500L;

    /** 用户档案 emotion_line 数组最多保留条数，超过丢最早。 */
    public static final int PROFILE_EMOTION_LINE_MAX = 50;

    /** Embedding 向量维度（BGE-M3 输出 1024 维）。 */
    public static final int EMBEDDING_DIM = 1024;

    private MemoryConstants() {}
}
