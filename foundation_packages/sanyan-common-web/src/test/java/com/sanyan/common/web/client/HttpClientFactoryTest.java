package com.sanyan.common.web.client;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpClientFactoryTest {

    @Test
    void newClient_shouldReturnNonNullRestClient() {
        RestClient client = HttpClientFactory.newClient(
                "https://example.com",
                Duration.ofSeconds(3),
                Duration.ofSeconds(10));
        assertThat(client).isNotNull();
    }

    @Test
    void newClient_shouldRejectNullBaseUrl() {
        assertThatThrownBy(() -> HttpClientFactory.newClient(null,
                Duration.ofSeconds(3), Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
