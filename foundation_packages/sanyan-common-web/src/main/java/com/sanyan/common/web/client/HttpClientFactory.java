package com.sanyan.common.web.client;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 通用 RestClient 工厂。配 connect/read timeout + JSON Content-Type 默认头 + baseUrl。
 *
 * <p>从原 sanyan-business/llm/internal/LlmHttpClients 改名上提到 foundation，
 * 让 llm-core / embedding-core 等业务方都能复用，避免每个模块自己重复一份。
 *
 * <p>调用方仍需为每次请求显式设置 {@code Authorization} / {@code X-Internal-Token}
 * 等私密 header，本工厂不知道凭证语义。
 */
public final class HttpClientFactory {

    private HttpClientFactory() {
        // utility class，禁止实例化
    }

    public static RestClient newClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        if (baseUrl == null) {
            throw new IllegalArgumentException("baseUrl must not be null");
        }
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
