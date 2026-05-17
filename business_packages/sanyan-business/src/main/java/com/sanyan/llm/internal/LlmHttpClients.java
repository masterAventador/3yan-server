package com.sanyan.llm.internal;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * LLM / Embedding 等上游 HTTP 服务的 {@link RestClient} 构建工具。
 *
 * <p>统一处理 connect/read timeout、{@code Content-Type: application/json} 默认头、baseUrl
 * 设置。{@link DeepSeekAdapter} 和 {@link RemoteBgeM3Provider} 共用此工厂以避免
 * 重复实现 {@link SimpleClientHttpRequestFactory} 配置——M1 code review 指出了这点。
 *
 * <p>按 CLAUDE.md "static 优先原则"：本类所有成员都是 static，不可实例化。
 */
public final class LlmHttpClients {

    private LlmHttpClients() {
        // utility class，禁止实例化
    }

    /**
     * 构建一个面向 JSON 上游服务的 {@link RestClient}。
     *
     * <p>注意：调用方仍需为每次请求显式设置 {@code Authorization} / {@code X-Internal-Token}
     * 等私密 header，本工厂不知道凭证语义。
     *
     * @param baseUrl       上游基础 URL（如 {@code https://api.deepseek.com} 或
     *                      {@code http://embedding-server.example:8080}）
     * @param connectTimeout 建立 TCP 连接的最大等待时间
     * @param readTimeout    单次请求等待响应字节的最大时间
     */
    public static RestClient newClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
