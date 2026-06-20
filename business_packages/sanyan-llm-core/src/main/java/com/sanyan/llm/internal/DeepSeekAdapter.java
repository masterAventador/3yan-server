package com.sanyan.llm.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sanyan.common.error.BusinessException;
import com.sanyan.common.web.client.HttpClientFactory;
import com.sanyan.llm.LlmTaskType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DeepSeek V4-Flash 适配器。
 *
 * <p>支持的 task type 由 {@code sanyan.llm.deepseek.task-types} 配置决定，默认
 * {@code USER_FACING,BACKGROUND}（2026-05-25 起接管所有任务）。
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

    /**
     * USER_FACING 任务的反重复解码参数：
     * 提高随机性 + 对重复 token/topic 加惩罚，抑制连续对话里的复读现象。
     * BACKGROUND 任务（JSON 抽取 / 摘要）不附加这些参数，保持结构化输出稳定。
     */
    private static final double USER_FACING_TEMPERATURE = 0.9;
    private static final double USER_FACING_FREQUENCY_PENALTY = 0.5;
    private static final double USER_FACING_PRESENCE_PENALTY = 0.3;

    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    /**
     * 配置驱动的 task type 列表（comma-separated，case-insensitive，trim 空格）。
     * 默认 USER_FACING,BACKGROUND = supports() 对所有 task 返回 true（2026-05-25 切 DeepSeek 后的默认行为）。
     * <p>Field-level @Value 而非构造器参数：
     * <ul>
     *   <li>不动现有构造器签名（最小侵入）</li>
     *   <li>非 final，便于 ReflectionTestUtils.setField 在测试里动态切值</li>
     * </ul>
     * <p>原始字符串只在 @PostConstruct 时解析一次，解析结果缓存到 {@link #parsedTaskTypes}。
     */
    @Value("${sanyan.llm.deepseek.task-types:USER_FACING,BACKGROUND}")
    private String taskTypes;

    /** {@link #taskTypes} 解析后的缓存（@PostConstruct 初始化，supports() 一次性查询）。 */
    private Set<LlmTaskType> parsedTaskTypes = Set.of();

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
        // M2c 重构：与 RemoteBgeM3Provider 共用 HttpClientFactory 工厂，消除重复的
        // RestClient + SimpleClientHttpRequestFactory 构造（M1 code review 指出的预警）
        this.restClient = HttpClientFactory.newClient(baseUrl, connectTimeout, readTimeout);
    }

    @PostConstruct
    void initTaskTypes() {
        this.parsedTaskTypes = LlmTaskTypeMatcher.parse(taskTypes);
        log.info("DeepSeekAdapter task types = {} (raw config: '{}')", parsedTaskTypes, taskTypes);
    }

    @Override
    public String chat(LlmTaskType taskType, List<Map<String, String>> chatMessages) {
        Map<String, Object> requestBody = buildRequestBody(taskType, chatMessages);

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

    /**
     * 按 taskType 构造请求体：{@code USER_FACING} 附加反重复解码参数抑制复读；
     * {@code BACKGROUND}（JSON 抽取 / 摘要）保持 model+messages 最小体，确保结构化输出稳定。
     */
    private Map<String, Object> buildRequestBody(LlmTaskType taskType, List<Map<String, String>> chatMessages) {
        if (taskType == LlmTaskType.USER_FACING) {
            return Map.of(
                    "model", model,
                    "messages", chatMessages,
                    "temperature", USER_FACING_TEMPERATURE,
                    "frequency_penalty", USER_FACING_FREQUENCY_PENALTY,
                    "presence_penalty", USER_FACING_PRESENCE_PENALTY);
        }
        return Map.of("model", model, "messages", chatMessages);
    }

    @Override
    public boolean supports(LlmTaskType taskType) {
        return parsedTaskTypes.contains(taskType);
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
