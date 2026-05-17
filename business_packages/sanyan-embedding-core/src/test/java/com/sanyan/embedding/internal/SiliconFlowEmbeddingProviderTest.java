package com.sanyan.embedding.internal;

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
 * {@link SiliconFlowEmbeddingProvider} 单元测试。
 *
 * <p>用 {@link MockWebServer} 模拟硅基流动 OpenAI 兼容 {@code POST /embeddings}，验证：
 * <ul>
 *   <li>200 成功：返回 vectors，请求头 {@code Authorization: Bearer <key>} +
 *       body {@code {model, input, encoding_format}}</li>
 *   <li>401：4xx 不重试，直接抛 {@link EmbeddingErrCode#EMBEDDING_SERVICE_UNAVAILABLE}</li>
 *   <li>503 重试后成功：第一次 503，第二次 200，调用方拿到成功结果（mockServer 收到 2 个 request）</li>
 *   <li>503 重试耗尽：连续 503，抛 {@code BusinessException(EMBEDDING_SERVICE_UNAVAILABLE)}</li>
 *   <li>连接 timeout：mockServer shutdown，重试耗尽后抛</li>
 *   <li>200 但响应缺 data：不重试，直接抛</li>
 * </ul>
 */
class SiliconFlowEmbeddingProviderTest {

    private static final String API_KEY = "sk-test-key-deadbeef";
    private static final String MODEL = "BAAI/bge-m3";
    private static final int CONNECT_TIMEOUT_MS = 1000;
    private static final int READ_TIMEOUT_MS = 1000;
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_BACKOFF_MS = 10;

    private MockWebServer mockServer;
    private SiliconFlowEmbeddingProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        provider = new SiliconFlowEmbeddingProvider(
                API_KEY,
                mockServer.url("").toString(),
                MODEL,
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
                .setBody("{\"object\":\"list\",\"model\":\"BAAI/bge-m3\","
                        + "\"data\":["
                        + "{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2,0.3]},"
                        + "{\"object\":\"embedding\",\"index\":1,\"embedding\":[0.4,0.5,0.6]}"
                        + "],"
                        + "\"usage\":{\"prompt_tokens\":4,\"total_tokens\":4}}"));

        List<float[]> vectors = provider.embed(List.of("你好", "世界"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(vectors.get(1)).containsExactly(0.4f, 0.5f, 0.6f);

        RecordedRequest request = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/embeddings");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + API_KEY);
        assertThat(request.getHeader("Content-Type")).contains("application/json");

        String body = request.getBody().readUtf8();
        // OpenAI 兼容请求体：{ "model": "...", "input": ["..."], "encoding_format": "float" }
        assertThat(body).contains("\"model\":\"" + MODEL + "\"");
        assertThat(body).contains("\"input\"");
        assertThat(body).contains("你好");
        assertThat(body).contains("世界");
        assertThat(body).contains("\"encoding_format\":\"float\"");
    }

    @Test
    void embed_shouldThrowOnUnauthorizedWithoutRetry() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("{\"error\":{\"message\":\"Invalid API key\"}}"));

        assertThatThrownBy(() -> provider.embed(List.of("hi")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(EmbeddingErrCode.EMBEDDING_SERVICE_UNAVAILABLE);

        // 4xx 不重试，只发出 1 次请求
        assertThat(mockServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void embed_shouldRetryAndSucceedOn503ThenOk() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(503).setBody("upstream busy"));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"object\":\"list\",\"model\":\"BAAI/bge-m3\","
                        + "\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.7,0.8]}],"
                        + "\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2}}"));

        List<float[]> vectors = provider.embed(List.of("retry test"));

        assertThat(vectors).hasSize(1);
        assertThat(vectors.get(0)).containsExactly(0.7f, 0.8f);
        assertThat(mockServer.getRequestCount()).isEqualTo(2);
    }

    @Test
    void embed_shouldThrowAfterRetriesExhaustedOn503() {
        mockServer.enqueue(new MockResponse().setResponseCode(503).setBody("busy 1"));
        mockServer.enqueue(new MockResponse().setResponseCode(503).setBody("busy 2"));
        mockServer.enqueue(new MockResponse().setResponseCode(503).setBody("busy 3"));

        assertThatThrownBy(() -> provider.embed(List.of("retry exhausted")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(EmbeddingErrCode.EMBEDDING_SERVICE_UNAVAILABLE);

        assertThat(mockServer.getRequestCount()).isEqualTo(MAX_RETRIES + 1);
    }

    @Test
    void embed_shouldThrowAfterRetriesExhaustedOnConnectFailure() throws IOException {
        mockServer.shutdown();

        assertThatThrownBy(() -> provider.embed(List.of("connect fail")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(EmbeddingErrCode.EMBEDDING_SERVICE_UNAVAILABLE);
    }

    @Test
    void embed_shouldThrowWhenResponseMissingData() throws Exception {
        // 200 但响应体没 data 字段（理论上硅基不会返这种，但防御性测试）
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"object\":\"list\",\"model\":\"BAAI/bge-m3\","
                        + "\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2}}"));

        assertThatThrownBy(() -> provider.embed(List.of("hi")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(EmbeddingErrCode.EMBEDDING_SERVICE_UNAVAILABLE);

        assertThat(mockServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void embed_shouldPreserveOrderAndDimensions() throws Exception {
        // 关键契约：3 条输入 → 返回 3 个向量，顺序与入参一致（OpenAI 协议 data[].index 保证）
        // 这里模拟硅基真实场景：1024 维向量，3 条输入
        StringBuilder body = new StringBuilder("{\"object\":\"list\",\"model\":\"BAAI/bge-m3\",\"data\":[");
        for (int i = 0; i < 3; i++) {
            if (i > 0) body.append(',');
            body.append("{\"object\":\"embedding\",\"index\":").append(i).append(",\"embedding\":[");
            for (int d = 0; d < 1024; d++) {
                if (d > 0) body.append(',');
                body.append(i + d * 0.001f);
            }
            body.append("]}");
        }
        body.append("],\"usage\":{\"prompt_tokens\":6,\"total_tokens\":6}}");

        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body.toString()));

        List<float[]> vectors = provider.embed(List.of("第一句", "第二句", "第三句"));

        assertThat(vectors).hasSize(3);
        assertThat(vectors.get(0)).hasSize(1024);
        assertThat(vectors.get(1)).hasSize(1024);
        assertThat(vectors.get(2)).hasSize(1024);
        // 顺序：index=0 的向量首位 ≈ 0，index=1 的 ≈ 1，index=2 的 ≈ 2
        assertThat(vectors.get(0)[0]).isCloseTo(0f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(vectors.get(1)[0]).isCloseTo(1f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(vectors.get(2)[0]).isCloseTo(2f, org.assertj.core.data.Offset.offset(0.001f));
    }
}
