/**
 * 远程 embedding 服务端实现（DJL + ONNX Runtime + BGE-M3）。
 *
 * <ul>
 *   <li>{@code web}：HTTP 入口（{@code EmbeddingController}）</li>
 *   <li>{@code internal}：模型加载（{@code BgeM3DjlAdapter}）、token 拦截器、健康指示器、错误码</li>
 *   <li>{@code api}：对内 Java 接口实现（{@code EmbeddingApiImpl}），委托 adapter</li>
 * </ul>
 *
 * <p>模型加载策略：{@code @EventListener(ApplicationReadyEvent.class)} 异步加载，
 * 期间 {@code /actuator/health} 返回 {@code OUT_OF_SERVICE}。
 */
package com.sanyan.embedding;
