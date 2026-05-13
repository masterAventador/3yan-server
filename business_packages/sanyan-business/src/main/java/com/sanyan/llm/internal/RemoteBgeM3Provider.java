package com.sanyan.llm.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sanyan.common.error.BusinessException;
import com.sanyan.common.web.BaseResp;
import com.sanyan.embedding.dto.EmbedRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * 远程 BGE-M3 embedding 服务的 HTTP 客户端实现。
 *
 * <p>调用部署在 old 服务器上的 {@code sanyan-embedding} 服务（M2b 任务建好的独立 Spring Boot 服务）。
 *
 * <p><b>错误码语义：</b>
 * <ul>
 *   <li>4xx（如 401 token 错 / 400 校验失败）→ {@link LlmErrCode#EMBEDDING_SERVICE_UNAVAILABLE}，
 *       **不重试**（重试也不会变成 2xx）</li>
 *   <li>5xx 上游错误 → 重试 {@code maxRetries} 次后仍失败则抛
 *       {@link LlmErrCode#EMBEDDING_SERVICE_UNAVAILABLE}</li>
 *   <li>网络异常（{@link ResourceAccessException}：连接拒绝 / DNS 失败 / read timeout）→ 同上重试</li>
 *   <li>200 但 {@code BaseResp.success=false} → 算业务错误，不重试，直接抛</li>
 * </ul>
 *
 * <p><b>重试实现：</b>程序化重试（手写 for-loop + 指数退避），不依赖 Spring AOP / RetryTemplate。
 * 原因：
 * <ul>
 *   <li>{@code @Retryable} 依赖 AOP 代理，单元测试中 {@code new RemoteBgeM3Provider(...)} 直接
 *       new 出来的实例没有代理，重试不会生效——会让"503 重试"测试无法验证。</li>
 *   <li>手写 for-loop 是普通 Java 代码，单测和生产行为完全一致，零代理依赖，也无需引入
 *       {@code spring-retry} / {@code spring-aspects} 这两个本来就用不上的依赖。</li>
 * </ul>
 *
 * <p><b>降级语义：</b>本类不返回空向量绕过 RAG，所有失败一律抛异常。降级由调用方
 * （P4 task 的 {@code MemoryRagSearchService}）根据业务语义决定。
 */
@Slf4j
@Component
public class RemoteBgeM3Provider implements EmbeddingProvider {

    private static final String EMBED_PATH = "/embed";
    /** {@code BaseResp<EmbedResponseData>} 反序列化的目标类型——避免 Jackson 把 generic 擦成 Object。 */
    private static final ParameterizedTypeReference<BaseResp<EmbedResponseData>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient http;
    private final String internalToken;
    private final int maxRetries;
    private final long retryBackoffMs;

    /**
     * Spring 注入用构造器。配置 key 见 {@code application-dev.yml}：
     * <pre>
     * sanyan.embedding.base-url=http://localhost:8080
     * sanyan.embedding.internal-token=${EMBEDDING_INTERNAL_TOKEN:}
     * sanyan.embedding.http.connect-timeout-ms=3000
     * sanyan.embedding.http.read-timeout-ms=10000
     * sanyan.embedding.http.max-retries=2
     * sanyan.embedding.http.retry-backoff-ms=500
     * </pre>
     */
    public RemoteBgeM3Provider(
            @Value("${sanyan.embedding.base-url:http://localhost:8080}") String baseUrl,
            @Value("${sanyan.embedding.internal-token:}") String internalToken,
            @Value("${sanyan.embedding.http.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${sanyan.embedding.http.read-timeout-ms:10000}") int readTimeoutMs,
            @Value("${sanyan.embedding.http.max-retries:2}") int maxRetries,
            @Value("${sanyan.embedding.http.retry-backoff-ms:500}") long retryBackoffMs) {
        this.http = LlmHttpClients.newClient(
                baseUrl,
                Duration.ofMillis(connectTimeoutMs),
                Duration.ofMillis(readTimeoutMs));
        this.internalToken = internalToken;
        this.maxRetries = maxRetries;
        this.retryBackoffMs = retryBackoffMs;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        EmbedRequest request = new EmbedRequest(texts);
        // attempt = 总尝试次数（含首次），最多 maxRetries + 1 次
        int totalAttempts = maxRetries + 1;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            try {
                return doEmbed(request);
            } catch (RetryableEmbeddingException e) {
                if (attempt >= totalAttempts) {
                    log.error("Embedding service unavailable after {} attempts: {}",
                            totalAttempts, e.getMessage());
                    throw new BusinessException(LlmErrCode.EMBEDDING_SERVICE_UNAVAILABLE);
                }
                long backoff = retryBackoffMs * (long) Math.pow(2, attempt - 1);
                log.warn("Embedding service attempt {}/{} failed ({}), retrying after {}ms",
                        attempt, totalAttempts, e.getMessage(), backoff);
                sleepQuiet(backoff);
            }
        }
        // 不可达：循环要么 return，要么从最后一次抛 BusinessException
        throw new BusinessException(LlmErrCode.EMBEDDING_SERVICE_UNAVAILABLE);
    }

    /**
     * 一次 HTTP 调用。把"可重试"和"立即抛"的失败分开：
     * <ul>
     *   <li>{@link RetryableEmbeddingException} → 外层重试</li>
     *   <li>{@link BusinessException} → 直接抛出（4xx / BaseResp 业务错误）</li>
     * </ul>
     */
    private List<float[]> doEmbed(EmbedRequest request) {
        try {
            BaseResp<EmbedResponseData> resp = http.post()
                    .uri(EMBED_PATH)
                    .header("X-Internal-Token", internalToken)
                    .body(request)
                    .retrieve()
                    .body(RESPONSE_TYPE);

            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                int code = resp == null ? -1 : resp.getCode();
                String message = resp == null ? "null body" : resp.getMessage();
                log.error("Embedding service business failure: code={}, message={}", code, message);
                throw new BusinessException(LlmErrCode.EMBEDDING_SERVICE_UNAVAILABLE);
            }
            return resp.getData().vectors();

        } catch (HttpClientErrorException e) {
            // 4xx 不重试，直接抛
            log.error("Embedding service 4xx (no retry): status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(LlmErrCode.EMBEDDING_SERVICE_UNAVAILABLE);
        } catch (HttpServerErrorException e) {
            // 5xx 标记可重试
            throw new RetryableEmbeddingException(
                    "upstream 5xx: " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            // 网络异常（连接拒绝 / DNS 失败 / read timeout）标记可重试
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

    /** 仅用于内部信号：表示当前尝试失败，但可重试。 */
    private static final class RetryableEmbeddingException extends RuntimeException {
        RetryableEmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * embedding-server 的 {@code POST /embed} 响应体 {@code BaseResp<EmbedResponseData>} 中的
     * {@code data} 字段。
     *
     * <p><b>为什么不直接复用 {@link com.sanyan.embedding.dto.EmbedResponse}？</b>
     * EmbedResponse 是 {@code record} 且字段没有 setter，Jackson 默认走"property 命名匹配 + setter"
     * 的反序列化通道，在某些 Spring Boot 配置下需要额外的 {@code @JsonCreator} 注解；用一个本地
     * record + {@link JsonIgnoreProperties} 更稳，且只取调用方真正需要的 vectors 字段（dim/latencyMs
     * 是上游监控字段，对本调用方无价值）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbedResponseData(List<float[]> vectors) {}
}
