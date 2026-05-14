package com.sanyan.memory.internal.rag;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sanyan.common.error.BusinessException;
import com.sanyan.llm.internal.EmbeddingProvider;
import com.sanyan.llm.internal.LlmErrCode;
import com.sanyan.memory.dto.MemoryFragment;
import com.sanyan.memory.internal.MemoryConstants;
import com.sanyan.memory.internal.MemoryErrCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Plan 2 Task P4：MemoryRagSearchService 单元测试。
 *
 * <p>纯 Mockito 单测，关注：
 * <ul>
 *   <li>正常路径：embed query → repository.findTopKByCosineSimilarity 透传 user/character/向量 +
 *       使用 {@link MemoryConstants#RAG_TOP_K} / {@link MemoryConstants#RAG_MIN_COS_SIM} 常量；
 *       返回 repository 给出的 fragment list</li>
 *   <li>降级路径 A：EmbeddingProvider 抛 BusinessException(MemoryErrCode.EMBEDDING_SERVICE_UNAVAILABLE)
 *       → 返回空 list + ERROR 日志</li>
 *   <li>降级路径 B：EmbeddingProvider 抛 BusinessException(LlmErrCode.EMBEDDING_SERVICE_UNAVAILABLE)
 *       → 返回空 list + ERROR 日志（M2c 的 RemoteBgeM3Provider 抛的是 LlmErrCode，code 4004）</li>
 *   <li>其他 BusinessException → 原样上抛（不在本层吞，由调用方 / GlobalExceptionHandler 处理）</li>
 *   <li>embed 返回空向量 list → 返回空 list（防御式：理论上 provider 不该返回空，但加防护避免越界）</li>
 * </ul>
 *
 * <p>Repository 的真实 native SQL 行为（cosine distance 操作符、top-K 排序、相似度过滤）由
 * {@link MemoryRagSearchServiceIT} 用 Testcontainers PG 覆盖。本单测只 mock repo 验证调用契约。
 *
 * <p>用 Logback {@link ListAppender} 捕获日志：spring-boot-starter-test 默认带 logback-classic，
 * 不引入额外 LogCaptor 依赖。绑定到 {@code MemoryRagSearchService} 自己的 logger 上。
 */
@ExtendWith(MockitoExtension.class)
class MemoryRagSearchServiceTest {

    @Mock
    private EmbeddingProvider embeddingProvider;

    @Mock
    private ChatEmbeddingRepository repository;

    @InjectMocks
    private MemoryRagSearchService service;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        serviceLogger = (Logger) LoggerFactory.getLogger(MemoryRagSearchService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logAppender);
    }

    @Test
    void search_shouldEmbedQueryAndDelegateToRepository() {
        float[] queryVec = new float[]{0.1f, 0.2f, 0.3f};
        when(embeddingProvider.embed(anyList())).thenReturn(List.of(queryVec));

        Instant t0 = Instant.parse("2026-05-01T12:00:00Z");
        MemoryFragment f1 = new MemoryFragment("聊过养猫的事", t0, 0.85);
        MemoryFragment f2 = new MemoryFragment("讨论过周末旅行", t0.plusSeconds(60), 0.72);
        when(repository.findTopKByCosineSimilarity(
                eq(100L), eq(7L), any(float[].class),
                eq(MemoryConstants.RAG_TOP_K), eq(MemoryConstants.RAG_MIN_COS_SIM)))
                .thenReturn(List.of(f1, f2));

        List<MemoryFragment> result = service.search(100L, 7L, "聊聊你养的猫吧");

        assertThat(result).containsExactly(f1, f2);

        // 验证 query 文本以单元素 list 形式传给 provider
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> textsCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingProvider).embed(textsCaptor.capture());
        assertThat(textsCaptor.getValue()).containsExactly("聊聊你养的猫吧");

        // 验证 repository 收到的就是 provider 返回的向量（同一引用）
        ArgumentCaptor<float[]> vecCaptor = ArgumentCaptor.forClass(float[].class);
        verify(repository).findTopKByCosineSimilarity(
                eq(100L), eq(7L), vecCaptor.capture(),
                eq(MemoryConstants.RAG_TOP_K), eq(MemoryConstants.RAG_MIN_COS_SIM));
        assertThat(vecCaptor.getValue()).isSameAs(queryVec);
    }

    @Test
    void search_shouldReturnEmptyListAndLogErrorWhenMemoryErrCodeEmbeddingUnavailable() {
        BusinessException providerError =
                new BusinessException(MemoryErrCode.EMBEDDING_SERVICE_UNAVAILABLE);
        when(embeddingProvider.embed(anyList())).thenThrow(providerError);

        List<MemoryFragment> result = service.search(100L, 7L, "随便聊点什么");

        assertThat(result).isEmpty();
        // 降级路径：repository 完全不该被触碰
        verify(repository, never())
                .findTopKByCosineSimilarity(any(), any(), any(), anyInt(), anyDouble());

        // ERROR 日志 + 异常堆栈被记录
        assertThat(logAppender.list)
                .as("应有 ERROR 日志")
                .anyMatch(ev -> ev.getLevel() == Level.ERROR
                        && ev.getFormattedMessage().contains("Embedding")
                        && ev.getThrowableProxy() != null);
    }

    @Test
    void search_shouldReturnEmptyListAndLogErrorWhenLlmErrCodeEmbeddingUnavailable() {
        // RemoteBgeM3Provider（M2c）抛的是 LlmErrCode.EMBEDDING_SERVICE_UNAVAILABLE（code 4004）
        // 本 service 不能因为 enum 不同就把它当未知异常上抛——按 code 识别降级
        BusinessException providerError =
                new BusinessException(LlmErrCode.EMBEDDING_SERVICE_UNAVAILABLE);
        when(embeddingProvider.embed(anyList())).thenThrow(providerError);

        List<MemoryFragment> result = service.search(100L, 7L, "test query");

        assertThat(result).isEmpty();
        verify(repository, never())
                .findTopKByCosineSimilarity(any(), any(), any(), anyInt(), anyDouble());
        assertThat(logAppender.list)
                .anyMatch(ev -> ev.getLevel() == Level.ERROR
                        && ev.getThrowableProxy() != null);
    }

    @Test
    void search_shouldRethrowOtherBusinessExceptions() {
        // 不是 embedding 不可用的异常（例如 LLM_UPSTREAM_4XX）——不该当作降级路径吞掉
        BusinessException unrelated = new BusinessException(LlmErrCode.LLM_UPSTREAM_4XX);
        when(embeddingProvider.embed(anyList())).thenThrow(unrelated);

        assertThatThrownBy(() -> service.search(100L, 7L, "test"))
                .isSameAs(unrelated);
        verifyNoInteractions(repository);
    }

    @Test
    void search_shouldReturnEmptyWhenEmbedReturnsEmptyList() {
        // 防御：理论上 provider 输入单 query 必返回单向量，但万一返回空别越界
        when(embeddingProvider.embed(anyList())).thenReturn(List.of());

        List<MemoryFragment> result = service.search(100L, 7L, "test");

        assertThat(result).isEmpty();
        verify(repository, never())
                .findTopKByCosineSimilarity(any(), any(), any(), anyInt(), anyDouble());
    }
}
