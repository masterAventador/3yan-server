package com.sanyan.llm.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sanyan.common.error.BusinessException;
import com.sanyan.common.web.client.HttpClientFactory;
import com.sanyan.llm.LlmTaskType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 豆包（火山引擎 ARK）适配器（USER_FACING 任务专用）。
 *
 * <p>豆包走 OpenAI 兼容协议：POST {@code <base-url>/chat/completions}，
 * body 为标准 OpenAI Chat Completion 格式，Authorization 用 Bearer 模式。
 *
 * <p>从 Plan 1 时代的 {@code AiService.callDoubao} 抽出，目的：
 * <ul>
 *   <li>统一 LLM 调用入口为 {@link LLMProvider}，由 {@link LLMProviderRouter} 按
 *       {@link LlmTaskType} 路由</li>
 *   <li>错误不再"吞异常返回 fallback 字符串"，与 {@link DeepSeekAdapter} 一致抛
 *       {@link BusinessException}；fallback 策略由调用方（Controller / 上层 Service）决定</li>
 * </ul>
 *
 * <p>错误码语义：
 * <ul>
 *   <li>4xx → {@link LlmErrCode#LLM_UPSTREAM_4XX}（鉴权 / 限流 / 请求非法）</li>
 *   <li>5xx / timeout / 网络异常 / 响应体异常 → {@link LlmErrCode#LLM_UPSTREAM_UNAVAILABLE}</li>
 * </ul>
 */
@Slf4j
@Component
public class DoubaoAdapter implements LLMProvider {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    /**
     * 配置驱动的 task type 列表（comma-separated，case-insensitive，trim 空格）。
     * 默认空 = supports() 全返回 false = 豆包不接任何任务（2026-05-25 切 DeepSeek 后的默认行为）。
     * <p>Field-level @Value 而非构造器参数：
     * <ul>
     *   <li>不动现有构造器签名（最小侵入）</li>
     *   <li>非 final，便于 ReflectionTestUtils.setField 在测试里动态切值</li>
     * </ul>
     */
    @Value("${sanyan.llm.doubao.task-types:}")
    private String taskTypes;

    /**
     * Spring 注入用构造器。配置 key 见 {@code application-dev.yml}：
     * <pre>
     * sanyan.doubao.api-key=${DOUBAO_API_KEY:}
     * sanyan.doubao.base-url=https://ark.cn-beijing.volces.com/api/v3
     * sanyan.doubao.model=ep-xxx-xxx
     * sanyan.doubao.connect-timeout=PT3S
     * sanyan.doubao.read-timeout=PT30S
     * </pre>
     *
     * <p>注意：豆包原始 endpoint 是 {@code .../api/v3/chat/completions}，本 adapter 把
     * {@code /api/v3} 作为 base-url 的一部分，路径常量只保留 {@code /chat/completions}——
     * 这样保持和 {@link DeepSeekAdapter} 一致的 base-url + path 拆分模式。
     */
    public DoubaoAdapter(
            @Value("${sanyan.doubao.api-key:}") String apiKey,
            @Value("${sanyan.doubao.base-url:https://ark.cn-beijing.volces.com/api/v3}") String baseUrl,
            @Value("${sanyan.doubao.model:doubao-seed-character}") String model,
            @Value("${sanyan.doubao.connect-timeout:PT3S}") Duration connectTimeout,
            @Value("${sanyan.doubao.read-timeout:PT30S}") Duration readTimeout) {
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = HttpClientFactory.newClient(baseUrl, connectTimeout, readTimeout);
    }

    @Override
    public String chat(List<Map<String, String>> chatMessages) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", chatMessages);

        try {
            long start = System.currentTimeMillis();
            DoubaoChatResponse response = restClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(requestBody)
                    .retrieve()
                    .body(DoubaoChatResponse.class);
            log.info("豆包 API 调用成功: model={}, messagesCount={}, 耗时={}ms",
                    model, chatMessages.size(), System.currentTimeMillis() - start);

            if (response == null || response.choices() == null || response.choices().isEmpty()
                    || response.choices().get(0).message() == null) {
                log.error("豆包返回 body 缺少 choices/message: {}", response);
                throw new BusinessException(LlmErrCode.LLM_UPSTREAM_UNAVAILABLE);
            }
            return response.choices().get(0).message().content();

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("豆包 API 上游响应错误: status={}, body={}", status, e.getResponseBodyAsString());
            if (status >= 400 && status < 500) {
                throw new BusinessException(LlmErrCode.LLM_UPSTREAM_4XX);
            }
            throw new BusinessException(LlmErrCode.LLM_UPSTREAM_UNAVAILABLE);
        } catch (ResourceAccessException e) {
            // 网络 timeout / 连接拒绝 / DNS 失败 等
            log.warn("豆包 API 网络异常: {}", e.getMessage());
            throw new BusinessException(LlmErrCode.LLM_UPSTREAM_UNAVAILABLE);
        }
    }

    @Override
    public boolean supports(LlmTaskType taskType) {
        if (taskTypes == null || taskTypes.isBlank()) {
            return false;
        }
        Set<String> configured = Arrays.stream(taskTypes.split(","))
                .map(s -> s.trim().toUpperCase())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return configured.contains(taskType.name());
    }

    @Override
    public String model() {
        return model;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DoubaoChatResponse(List<Choice> choices) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Choice(Message message) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Message(String role, String content) {}
    }
}
