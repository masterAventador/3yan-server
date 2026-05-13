package com.sanyan.llm.internal;

import com.sanyan.common.error.BusinessException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task M2c：{@link RemoteBgeM3Provider} 单元测试。
 *
 * <p>用 {@link MockWebServer} 模拟远程 embedding-server，验证：
 * <ul>
 *   <li>200 成功：返回 vectors，请求头带 {@code X-Internal-Token} + body 是 {@code EmbedRequest} JSON</li>
 *   <li>401：4xx 不重试，直接抛 {@code BusinessException(EMBEDDING_SERVICE_UNAVAILABLE)}</li>
 *   <li>503 重试后成功：第一次 503，第二次 200，调用方拿到成功结果（mockServer 收到 2 个 request）</li>
 *   <li>503 重试耗尽：连续 503，抛 {@code BusinessException(EMBEDDING_SERVICE_UNAVAILABLE)}（总共 maxRetries+1 次 request）</li>
 *   <li>连接 timeout：mockServer shutdown，重试耗尽后抛 {@code BusinessException(EMBEDDING_SERVICE_UNAVAILABLE)}</li>
 * </ul>
 */
class RemoteBgeM3ProviderTest {

    private static final String INTERNAL_TOKEN = "test-internal-token-deadbeef";
    private static final int CONNECT_TIMEOUT_MS = 1000;
    private static final int READ_TIMEOUT_MS = 1000;
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_BACKOFF_MS = 10;  // 测试里把退避压到 10ms，避免拖慢测试

    private MockWebServer mockServer;
    private RemoteBgeM3Provider provider;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        provider = new RemoteBgeM3Provider(
                mockServer.url("").toString(),
                INTERNAL_TOKEN,
                CONNECT_TIMEOUT_MS,
                READ_TIMEOUT_MS,
                MAX_RETRIES,
                RETRY_BACKOFF_MS);
    }

    @AfterEach
    void tearDown() throws IOException {
        try {
            mockServer.shutdown();
        } catch (IllegalStateException ignored) {
            // 已经 shutdown（timeout 测试场景）
        }
    }

    @Test
    void embed_shouldPostAndReturnVectors() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"code\":0,\"message\":\"ok\",\"data\":{"
                        + "\"vectors\":[[0.1,0.2,0.3],[0.4,0.5,0.6]],"
                        + "\"dim\":3,"
                        + "\"latencyMs\":15}}"));

        List<float[]> vectors = provider.embed(List.of("你好", "世界"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(vectors.get(1)).containsExactly(0.4f, 0.5f, 0.6f);

        RecordedRequest request = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/embed");
        assertThat(request.getHeader("X-Internal-Token")).isEqualTo(INTERNAL_TOKEN);
        assertThat(request.getHeader("Content-Type")).contains("application/json");

        String body = request.getBody().readUtf8();
        // EmbedRequest 序列化为 { "texts": [...] }
        assertThat(body).contains("\"texts\"");
        assertThat(body).contains("你好");
        assertThat(body).contains("世界");
    }

    @Test
    void embed_shouldThrowOnUnauthorizedWithoutRetry() throws Exception {
        // 401 token 错——不应重试，直接抛
        mockServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("{\"success\":false,\"code\":6001,\"message\":\"invalid token\"}"));

        assertThatThrownBy(() -> provider.embed(List.of("hi")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(LlmErrCode.EMBEDDING_SERVICE_UNAVAILABLE);

        // 关键：4xx 不重试，只发出 1 次请求
        assertThat(mockServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void embed_shouldRetryAndSucceedOn503ThenOk() throws Exception {
        // 序列：第一次 503，第二次 200
        mockServer.enqueue(new MockResponse().setResponseCode(503).setBody("upstream busy"));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"code\":0,\"message\":\"ok\",\"data\":{"
                        + "\"vectors\":[[0.7,0.8]],"
                        + "\"dim\":2,"
                        + "\"latencyMs\":20}}"));

        List<float[]> vectors = provider.embed(List.of("retry test"));

        assertThat(vectors).hasSize(1);
        assertThat(vectors.get(0)).containsExactly(0.7f, 0.8f);

        // 总共发出 2 个请求：1 次失败 + 1 次成功 = 重试 1 次
        assertThat(mockServer.getRequestCount()).isEqualTo(2);
    }

    @Test
    void embed_shouldThrowAfterRetriesExhaustedOn503() {
        // maxRetries=2 → 总共最多发 maxRetries+1 = 3 次请求
        mockServer.enqueue(new MockResponse().setResponseCode(503).setBody("busy 1"));
        mockServer.enqueue(new MockResponse().setResponseCode(503).setBody("busy 2"));
        mockServer.enqueue(new MockResponse().setResponseCode(503).setBody("busy 3"));

        assertThatThrownBy(() -> provider.embed(List.of("retry exhausted")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(LlmErrCode.EMBEDDING_SERVICE_UNAVAILABLE);

        assertThat(mockServer.getRequestCount()).isEqualTo(MAX_RETRIES + 1);
    }

    @Test
    void embed_shouldThrowAfterRetriesExhaustedOnConnectFailure() throws IOException {
        // 模拟服务完全不可达：先关掉 mockServer，再调
        mockServer.shutdown();

        assertThatThrownBy(() -> provider.embed(List.of("connect fail")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(LlmErrCode.EMBEDDING_SERVICE_UNAVAILABLE);
    }

    @Test
    void embed_shouldThrowWhenResponseBodyHasSuccessFalse() throws Exception {
        // 上游 200 但 BaseResp.success=false（如服务器侧业务错误：模型未 ready 等）
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":false,\"code\":6002,\"message\":\"model not ready\",\"data\":null}"));

        assertThatThrownBy(() -> provider.embed(List.of("hi")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(LlmErrCode.EMBEDDING_SERVICE_UNAVAILABLE);

        // 200 算成功通信，不触发重试
        assertThat(mockServer.getRequestCount()).isEqualTo(1);
    }
}
