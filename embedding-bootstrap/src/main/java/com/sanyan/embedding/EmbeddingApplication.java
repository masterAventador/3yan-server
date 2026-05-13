package com.sanyan.embedding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Embedding 服务的 Spring Boot 启动类。
 *
 * Plan 2 Task L0：Stub，让 fat jar 能成功 repackage + 启动空 context。
 * Task M2b 会扩展为加载 fastembed-java / BGE-M3 模型 + Controller。
 *
 * <p>{@code @EnableAsync}：开启 Spring 的 {@code @Async} 支持，让
 * {@link com.sanyan.embedding.internal.BgeM3DjlAdapter#loadModel()} 能在
 * {@code ApplicationReadyEvent} 触发后异步加载模型，避免阻塞启动。
 *
 * 部署目标：old 服务器（154.8.162.83），systemd 守护，监听 8080。
 */
@SpringBootApplication
@EnableAsync
public class EmbeddingApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmbeddingApplication.class, args);
    }
}
