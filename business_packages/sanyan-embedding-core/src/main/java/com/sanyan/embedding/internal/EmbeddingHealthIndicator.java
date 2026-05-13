package com.sanyan.embedding.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 自定义健康检查：模型未加载完成时返回 {@code OUT_OF_SERVICE}。
 *
 * <p>Spring Boot Actuator 会自动发现这个 Bean 并把它纳入 {@code /actuator/health}
 * 的整体状态（按最坏状态取）。所以 embedding-server 首次启动期间，外部
 * 探活将看到 {@code 503 OUT_OF_SERVICE}，直到模型 ready 才转 {@code UP}——
 * 这正是 plan 期望的语义。
 *
 * <p>不在这里跑 {@code adapter.embed("ping")}：那会增加每次健康检查的延迟，
 * 而 ready 标记本身已经是模型加载完成的可靠信号。
 */
@Component("embeddingHealthIndicator")
@RequiredArgsConstructor
public class EmbeddingHealthIndicator implements HealthIndicator {

    private final BgeM3DjlAdapter adapter;

    @Override
    public Health health() {
        if (adapter.isReady()) {
            return Health.up()
                    .withDetail("modelReady", true)
                    .build();
        }
        return Health.outOfService()
                .withDetail("modelReady", false)
                .withDetail("reason", "BGE-M3 模型尚未加载完成")
                .build();
    }
}
