package com.sanyan.embedding.web;

import com.sanyan.common.web.BaseResp;
import com.sanyan.embedding.EmbeddingApi;
import com.sanyan.embedding.dto.EmbedRequest;
import com.sanyan.embedding.dto.EmbedResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Embedding 服务 HTTP 入口。
 *
 * <p>POST {@code /embed}：把 {@link EmbedRequest#texts()} 批量送 {@link EmbeddingApi}，
 * 返回 {@link EmbedResponse}（包含向量、维度、本次耗时）。
 *
 * <p>token 校验由 {@code InternalTokenInterceptor} 在进入本 Controller 前完成；
 * 参数校验由 {@code @Valid} + Bean Validation 在 deserialization 后触发；
 * 失败时由 {@code GlobalExceptionHandler} 统一转 {@link BaseResp#failed}。
 */
@Slf4j
@RestController
@RequestMapping("/embed")
@RequiredArgsConstructor
public class EmbeddingController {

    private final EmbeddingApi embeddingApi;

    @PostMapping
    public BaseResp<EmbedResponse> embed(@Valid @RequestBody EmbedRequest request) {
        String requestId = UUID.randomUUID().toString();
        int textCount = request.texts().size();
        long start = System.currentTimeMillis();

        List<float[]> vectors = embeddingApi.embed(request.texts());
        long latencyMs = System.currentTimeMillis() - start;
        int dim = vectors.isEmpty() ? 0 : vectors.get(0).length;

        log.info("request_id={} latency_ms={} text_count={} dim={}",
                requestId, latencyMs, textCount, dim);

        return BaseResp.success(new EmbedResponse(vectors, dim, latencyMs));
    }
}
