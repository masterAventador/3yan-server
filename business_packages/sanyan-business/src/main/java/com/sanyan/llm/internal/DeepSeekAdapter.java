package com.sanyan.llm.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sanyan.common.error.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek V4-Flash 适配器（BACKGROUND 任务专用）。
 *
 * <p>DeepSeek 走 OpenAI 兼容协议：POST {@code <base-url>/chat/completions}，
 * body 为标准 OpenAI Chat Completion 格式，Authorization 用 Bearer 模式。
 *
 * <p>错误码语义：
 * <ul>
 *   <li>4xx → {@link LlmErrCode#LLM_UPSTREAM_4XX}（鉴权 / 限流 / 请求非法）</li>
 *   <li>5xx / timeout / 网络异常 → {@link LlmErrCode#LLM_UPSTREAM_UNAVAILABLE}（M2c task 加重试）</li>
 * </ul>
 */
@Slf4j
@Component
public class DeepSeekAdapter implements LLMProvider {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    /**
     * Spring 注入用构造器。配置 key 形如：
     * <pre>
     * sanyan.llm.deepseek.api-key=${DEEPSEEK_API_KEY:}
     * sanyan.llm.deepseek.base-url=https://api.deepseek.com
     * sanyan.llm.deepseek.model=deepseek-v4-flash
     * sanyan.llm.deepseek.connect-timeout=PT3S
     * sanyan.llm.deepseek.read-timeout=PT30S
     * </pre>
     */
    public DeepSeekAdapter(
            @Value("${sanyan.llm.deepseek.api-key:}") String apiKey,
            @Value("${sanyan.llm.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${sanyan.llm.deepseek.model:deepseek-v4-flash}") String model,
            @Value("${sanyan.llm.deepseek.connect-timeout:PT3S}") Duration connectTimeout,
            @Value("${sanyan.llm.deepseek.read-timeout:PT30S}") Duration readTimeout) {
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = buildRestClient(baseUrl, connectTimeout, readTimeout);
    }

    private static RestClient buildRestClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String chat(List<Map<String, String>> chatMessages) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", chatMessages);

        try {
            long start = System.currentTimeMillis();
            DeepSeekChatResponse response = restClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(requestBody)
                    .retrieve()
                    .body(DeepSeekChatResponse.class);
            log.info("DeepSeek API 调用成功: model={}, messagesCount={}, 耗时={}ms",
                    model, chatMessages.size(), System.currentTimeMillis() - start);

            if (response == null || response.choices() == null || response.choices().isEmpty()
                    || response.choices().get(0).message() == null) {
                log.error("DeepSeek 返回 body 缺少 choices/message: {}", response);
                throw new BusinessException(LlmErrCode.LLM_UPSTREAM_UNAVAILABLE);
            }
            return response.choices().get(0).message().content();

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("DeepSeek API 上游响应错误: status={}, body={}", status, e.getResponseBodyAsString());
            if (status >= 400 && status < 500) {
                throw new BusinessException(LlmErrCode.LLM_UPSTREAM_4XX);
            }
            throw new BusinessException(LlmErrCode.LLM_UPSTREAM_UNAVAILABLE);
        } catch (ResourceAccessException e) {
            // 网络 timeout / 连接拒绝 / DNS 失败 等
            log.warn("DeepSeek API 网络异常: {}", e.getMessage());
            throw new BusinessException(LlmErrCode.LLM_UPSTREAM_UNAVAILABLE);
        }
    }

    @Override
    public boolean supports(LLMTaskType taskType) {
        return taskType == LLMTaskType.BACKGROUND;
    }

    @Override
    public String model() {
        return model;
    }

    // 用 record 反序列化 OpenAI 兼容响应体的有用字段，其它字段忽略
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DeepSeekChatResponse(List<Choice> choices) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Choice(Message message) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Message(String role, String content) {}
    }
}
