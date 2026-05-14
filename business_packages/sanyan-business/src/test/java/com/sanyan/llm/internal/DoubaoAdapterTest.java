package com.sanyan.llm.internal;

import com.sanyan.common.error.BusinessException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task M3：DoubaoAdapter 单元测试。
 *
 * <p>用 {@link MockWebServer} 模拟豆包（火山引擎 ARK）OpenAI 兼容 /chat/completions endpoint，
 * 验证 adapter 在 200 / 401 / 429 / 500 / 网络异常各种情况下的行为。
 *
 * <p>豆包返回错误时不再"吞异常返回 fallback 字符串"，而是统一抛 {@link BusinessException}，
 * 与 {@link DeepSeekAdapter} 保持一致——降级策略交给调用方（AiService / Controller 层）决定。
 */
class DoubaoAdapterTest {

    private MockWebServer mockServer;
    private DoubaoAdapter adapter;

    private static final String API_KEY = "doubao-test-key";
    private static final String MODEL = "doubao-seed-character";

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        adapter = new DoubaoAdapter(
                API_KEY,
                mockServer.url("").toString(),
                MODEL,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    void supports_shouldReturnTrueForUserFacingOnly() {
        assertThat(adapter.supports(LLMTaskType.USER_FACING)).isTrue();
        assertThat(adapter.supports(LLMTaskType.BACKGROUND)).isFalse();
    }

    @Test
    void model_shouldExposeConfiguredModelName() {
        assertThat(adapter.model()).isEqualTo(MODEL);
    }

    @Test
    void chat_shouldPostToChatCompletionsAndReturnContent() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"嗨，我是小婉\"}}]}"));

        String reply = adapter.chat(List.of(
                Map.of("role", "system", "content", "你是小婉"),
                Map.of("role", "user", "content", "你好")));

        assertThat(reply).isEqualTo("嗨，我是小婉");

        RecordedRequest request = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/chat/completions");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + API_KEY);
        assertThat(request.getHeader("Content-Type")).contains("application/json");

        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"model\":\"" + MODEL + "\"");
        assertThat(body).contains("\"role\":\"system\"");
        assertThat(body).contains("\"role\":\"user\"");
        assertThat(body).contains("你好");
    }

    @Test
    void chat_shouldThrowOnUnauthorized() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"Invalid API key\"}}"));

        assertThatThrownBy(() -> adapter.chat(
                List.of(Map.of("role", "user", "content", "hi"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(LlmErrCode.LLM_UPSTREAM_4XX);
    }

    @Test
    void chat_shouldThrowOnRateLimited() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"Rate limit exceeded\"}}"));

        assertThatThrownBy(() -> adapter.chat(
                List.of(Map.of("role", "user", "content", "hi"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(LlmErrCode.LLM_UPSTREAM_4XX);
    }

    @Test
    void chat_shouldThrowOnServerError() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"Internal server error\"}}"));

        assertThatThrownBy(() -> adapter.chat(
                List.of(Map.of("role", "user", "content", "hi"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(LlmErrCode.LLM_UPSTREAM_UNAVAILABLE);
    }

    @Test
    void chat_shouldThrowOnNetworkError() throws IOException {
        mockServer.shutdown();

        assertThatThrownBy(() -> adapter.chat(
                List.of(Map.of("role", "user", "content", "hi"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(LlmErrCode.LLM_UPSTREAM_UNAVAILABLE);
    }

    @Test
    void chat_shouldThrowOnMalformedResponseBody() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"unexpected\":\"shape\"}"));

        assertThatThrownBy(() -> adapter.chat(
                List.of(Map.of("role", "user", "content", "hi"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(LlmErrCode.LLM_UPSTREAM_UNAVAILABLE);
    }
}
