package com.sanyan.llm.internal;

import com.sanyan.common.error.BusinessException;
import com.sanyan.llm.LlmTaskType;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task M1：DeepSeekAdapter 单元测试。
 *
 * <p>用 {@link MockWebServer} 模拟 DeepSeek 的 OpenAI 兼容 /chat/completions endpoint，
 * 验证 adapter 在 200 / 401 / 429 / 500 / 网络 timeout 各种情况下的行为。
 */
class DeepSeekAdapterTest {

    private MockWebServer mockServer;
    private DeepSeekAdapter adapter;

    private static final String API_KEY = "sk-test-deepseek-key";
    private static final String MODEL = "deepseek-v4-flash";

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        adapter = new DeepSeekAdapter(
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
    void supports_shouldReturnFalseForAllWhenTaskTypesIsEmpty() {
        ReflectionTestUtils.setField(adapter, "parsedTaskTypes", Set.of());
        assertThat(adapter.supports(LlmTaskType.USER_FACING)).isFalse();
        assertThat(adapter.supports(LlmTaskType.BACKGROUND)).isFalse();
    }

    @Test
    void supports_shouldReturnTrueOnlyForConfiguredTaskType() {
        ReflectionTestUtils.setField(adapter, "parsedTaskTypes", Set.of(LlmTaskType.USER_FACING));
        assertThat(adapter.supports(LlmTaskType.USER_FACING)).isTrue();
        assertThat(adapter.supports(LlmTaskType.BACKGROUND)).isFalse();
    }

    @Test
    void supports_shouldReturnTrueForAllConfiguredTaskTypes() {
        ReflectionTestUtils.setField(adapter, "parsedTaskTypes",
                Set.of(LlmTaskType.USER_FACING, LlmTaskType.BACKGROUND));
        assertThat(adapter.supports(LlmTaskType.USER_FACING)).isTrue();
        assertThat(adapter.supports(LlmTaskType.BACKGROUND)).isTrue();
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
                        + "\"content\":\"你好，我是 DeepSeek\"}}]}"));

        String reply = adapter.chat(List.of(
                Map.of("role", "system", "content", "你是后台摘要助手"),
                Map.of("role", "user", "content", "总结一下今天的对话")));

        assertThat(reply).isEqualTo("你好，我是 DeepSeek");

        RecordedRequest request = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/chat/completions");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + API_KEY);
        assertThat(request.getHeader("Content-Type")).contains("application/json");

        String body = request.getBody().readUtf8();
        // 模型名和 messages 数组必须被正确发出
        assertThat(body).contains("\"model\":\"" + MODEL + "\"");
        assertThat(body).contains("\"role\":\"system\"");
        assertThat(body).contains("\"role\":\"user\"");
        assertThat(body).contains("总结一下今天的对话");
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
    void chat_shouldThrowOnReadTimeout() throws IOException {
        // 关掉 mock server 模拟连接不通——会触发 RestClient 的连接异常
        mockServer.shutdown();

        assertThatThrownBy(() -> adapter.chat(
                List.of(Map.of("role", "user", "content", "hi"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(LlmErrCode.LLM_UPSTREAM_UNAVAILABLE);
    }
}
