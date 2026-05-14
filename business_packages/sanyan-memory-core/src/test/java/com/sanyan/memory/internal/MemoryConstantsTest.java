package com.sanyan.memory.internal;

import com.sanyan.memory.MemoryConstants;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 2 Task N0：守护 Memory 模块的"对齐铁律"。
 *
 * 这三条断言阻止后续 task 改坏关键常量造成"夹缝消息"或 HTTP 配置不合理：
 *
 * 1. SHORT_TERM_WINDOW_SIZE 必须严格 &gt; SUMMARY_TRIGGER_THRESHOLD —— 防止短期窗口够不到刚好还没被异步摘要落库的消息
 * 2. RAG_CHUNK_MAX_SIZE &gt;= RAG_CHUNK_MIN_SIZE —— chunk 范围必须自洽
 * 3. EMBEDDING_HTTP_READ_TIMEOUT_MS &gt; EMBEDDING_HTTP_CONNECT_TIMEOUT_MS —— 读超时必须比连接超时长，否则连得上但读不完
 */
class MemoryConstantsTest {

    @Test
    void shortTermWindowMustBeStrictlyGreaterThanSummaryThreshold() {
        assertThat(MemoryConstants.SHORT_TERM_WINDOW_SIZE)
            .as("对齐铁律：短期窗口必须严格 > 摘要阈值，防止夹缝消息（见 plan 关键常量段）")
            .isGreaterThan(MemoryConstants.SUMMARY_TRIGGER_THRESHOLD);
    }

    @Test
    void ragChunkMaxMustBeGreaterOrEqualMin() {
        assertThat(MemoryConstants.RAG_CHUNK_MAX_SIZE)
            .isGreaterThanOrEqualTo(MemoryConstants.RAG_CHUNK_MIN_SIZE);
    }

    @Test
    void httpReadTimeoutMustBeGreaterThanConnectTimeout() {
        assertThat(MemoryConstants.EMBEDDING_HTTP_READ_TIMEOUT_MS)
            .isGreaterThan(MemoryConstants.EMBEDDING_HTTP_CONNECT_TIMEOUT_MS);
    }
}
