package com.sanyan.embedding;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 测试专用 Spring Boot 配置根。
 *
 * <p>本模块（sanyan-embedding-core）没有 main 类（main 在 embedding-bootstrap），
 * {@code @WebMvcTest} 需要一个 {@code @SpringBootApplication} 才能定位上下文 + 启用
 * 包扫描。{@code @SpringBootApplication} = {@code @SpringBootConfiguration} +
 * {@code @EnableAutoConfiguration} + {@code @ComponentScan}（覆盖 {@code com.sanyan.embedding} 包）。
 *
 * <p>放在 {@code com.sanyan.embedding} 包根：扫描覆盖 web / internal / api 三个子包，
 * 让 {@code EmbeddingController} / {@code InternalTokenInterceptor} / {@code EmbeddingApiImpl} / {@code BgeM3DjlAdapter}
 * 均能被找到。
 */
@SpringBootApplication
public class EmbeddingTestApplication {}
