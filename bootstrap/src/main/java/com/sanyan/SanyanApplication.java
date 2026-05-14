package com.sanyan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 三言 server 启动入口。
 *
 * <p>{@code @EnableAsync}（Plan 2 Task N3 引入）：开启 Spring 的 {@code @Async} 支持，
 * 让 {@code SummaryScheduler} / 后续 P 阶段的档案抽取 listener / RAG 索引 listener
 * 都能用 {@code @Async} + {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 异步执行，
 * 避免阻塞主对话链路。
 */
@SpringBootApplication
@EnableAsync
public class SanyanApplication {
    public static void main(String[] args) {
        SpringApplication.run(SanyanApplication.class, args);
    }
}
