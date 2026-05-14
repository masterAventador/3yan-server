package com.sanyan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 三言 server 启动入口。
 *
 * <p>{@code @EnableAsync}（Plan 2 Task N3 引入）：开启 Spring 的 {@code @Async} 支持，
 * 让 {@code SummaryScheduler} / 后续 P 阶段的档案抽取 listener / RAG 索引 listener
 * 都能用 {@code @Async} + {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 异步执行，
 * 避免阻塞主对话链路。
 *
 * <p>{@code @EnableScheduling}（Plan 2 Task P5 引入）：开启 Spring 的 {@code @Scheduled} 支持，
 * 让 {@code RagIndexWorker} 通过 {@code @Scheduled(fixedDelay=10000)} 每 10 秒 polling
 * 消费 Redis Stream {@code sanyan:memory:rag:index-queue}，把切好的 chunk 异步落库到 pgvector。
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class SanyanApplication {
    public static void main(String[] args) {
        SpringApplication.run(SanyanApplication.class, args);
    }
}
