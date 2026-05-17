package com.sanyan.embedding.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sanyan.common.error.BusinessException;
import com.sanyan.common.web.client.HttpClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 硅基流动（SiliconFlow）OpenAI 兼容 embedding 接口的 HTTP 客户端实现。
 *
 * <p>调用 {@code POST https://api.siliconflow.cn/v1/embeddings}，默认走免费的
 * {@code BAAI/bge-m3} 模型——和此前自建的本地 BGE-M3 服务向量空间一致
 * （余弦相似度 ≈ 1.0），切换后老数据无需重算。
 *
 * <p><b>错误码语义</b>（与历史 {@code RemoteBgeM3Provider} 完全对齐）：
 * <ul>
 *   <li>4xx（401 key 错 / 429 限流 / 400 请求非法）→ {@link EmbeddingErrCode#EMBEDDING_SERVICE_UNAVAILABLE}，
 *       <b>不重试</b>（重试不会变成 2xx）</li>
 *   <li>5xx 上游错误 → 重试 {@code maxRetries} 次后仍失败抛 {@link EmbeddingErrCode#EMBEDDING_SERVICE_UNAVAILABLE}</li>
 *   <li>网络异常（{@link ResourceAccessException}：连接拒绝 / DNS 失败 / read timeout）→ 同上重试</li>
 *   <li>200 但响应体缺 data 字段 → 算业务错误，不重试，直接抛</li>
 * </ul>
 *
 * <p><b>重试实现</b>：手写 for-loop + 指数退避，不依赖 Spring AOP / RetryTemplate——
 * 理由与 {@code RemoteBgeM3Provider} 同（单元测试 {@code new} 出来的实例没代理）。
 *
 * <p><b>降级语义</b>：本类不返回空向量绕过 RAG，所有失败一律抛异常。降级由调用方
 * （{@code MemoryRagSearchService}）根据业务语义决定。
 *
 * <p><b>S3 Phase 4</b>：本类从 {@code sanyan-business/llm/internal} 迁到
 * {@code sanyan-embedding-core/embedding/internal}；同时不再 implements {@code EmbeddingProvider}
 * 接口（旧接口已删除）。本类作为 internal 委托对象，由 {@code EmbeddingApiImpl} 包装暴露给外部。
 */
@Slf4j
@Component
public class SiliconFlowEmbeddingProvider {

    private static final String EMBEDDINGS_PATH = "/embeddings";
    private static final String ENCODING_FORMAT = "float";

    private final RestClient http;
    private final String apiKey;
    private final String model;
    private final int maxRetries;
    private final long retryBackoffMs;

    /**
     * Spring 注入用构造器。配置 key 见 {@code application.yml}：
     * <pre>
     * sanyan.embedding.siliconflow.api-key=${SILICONFLOW_API_KEY:}
     * sanyan.embedding.siliconflow.base-url=https://api.siliconflow.cn/v1
     * sanyan.embedding.siliconflow.model=BAAI/bge-m3
     * sanyan.embedding.http.connect-timeout-ms=3000
     * sanyan.embedding.http.read-timeout-ms=10000
     * sanyan.embedding.http.max-retries=2
     * sanyan.embedding.http.retry-backoff-ms=500
     * </pre>
     */
    public SiliconFlowEmbeddingProvider(
            @Value("${sanyan.embedding.siliconflow.api-key:}") String apiKey,
            @Value("${sanyan.embedding.siliconflow.base-url:https://api.siliconflow.cn/v1}") String baseUrl,
            @Value("${sanyan.embedding.siliconflow.model:BAAI/bge-m3}") String model,
            @Value("${sanyan.embedding.http.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${sanyan.embedding.http.read-timeout-ms:10000}") int readTimeoutMs,
            @Value("${sanyan.embedding.http.max-retries:2}") int maxRetries,
            @Value("${sanyan.embedding.http.retry-backoff-ms:500}") long retryBackoffMs) {
        this.http = HttpClientFactory.newClient(
                baseUrl,
                Duration.ofMillis(connectTimeoutMs),
                Duration.ofMillis(readTimeoutMs));
        this.apiKey = apiKey;
        this.model = model;
        this.maxRetries = maxRetries;
        this.retryBackoffMs = retryBackoffMs;
    }

    public List<float[]> embed(List<String> texts) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "input", texts,
                "encoding_format", ENCODING_FORMAT);

        int totalAttempts = maxRetries + 1;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            try {
                return doEmbed(requestBody);
            } catch (RetryableEmbeddingException e) {
                if (attempt >= totalAttempts) {
                    log.error("SiliconFlow embedding unavailable after {} attempts: {}",
                            totalAttempts, e.getMessage());
                    throw new BusinessException(EmbeddingErrCode.EMBEDDING_SERVICE_UNAVAILABLE);
                }
                long backoff = retryBackoffMs * (long) Math.pow(2, attempt - 1);
                log.warn("SiliconFlow embedding attempt {}/{} failed ({}), retrying after {}ms",
                        attempt, totalAttempts, e.getMessage(), backoff);
                sleepQuiet(backoff);
            }
        }
        throw new BusinessException(EmbeddingErrCode.EMBEDDING_SERVICE_UNAVAILABLE);
    }

    private List<float[]> doEmbed(Map<String, Object> requestBody) {
        try {
            EmbeddingsResponse resp = http.post()
                    .uri(EMBEDDINGS_PATH)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(requestBody)
                    .retrieve()
                    .body(EmbeddingsResponse.class);

            if (resp == null || resp.data() == null || resp.data().isEmpty()) {
                log.error("SiliconFlow response missing data: {}", resp);
                throw new BusinessException(EmbeddingErrCode.EMBEDDING_SERVICE_UNAVAILABLE);
            }
            return resp.data().stream().map(EmbeddingItem::embedding).toList();

        } catch (HttpClientErrorException e) {
            log.error("SiliconFlow 4xx (no retry): status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(EmbeddingErrCode.EMBEDDING_SERVICE_UNAVAILABLE);
        } catch (HttpServerErrorException e) {
            throw new RetryableEmbeddingException(
                    "upstream 5xx: " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new RetryableEmbeddingException("network error: " + e.getMessage(), e);
        }
    }

    private static void sleepQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class RetryableEmbeddingException extends RuntimeException {
        RetryableEmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * OpenAI 兼容 embeddings 响应体（只保留调用方需要的 data 字段）。
     * 完整 schema 见 https://platform.openai.com/docs/api-reference/embeddings/object
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingsResponse(List<EmbeddingItem> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingItem(float[] embedding) {}
}
