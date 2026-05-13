package com.sanyan.embedding.dto;

import java.util.List;

/**
 * 远程 embedding 服务的响应体。
 *
 * @param vectors  每条输入 text 对应的浮点向量；顺序与请求 texts 一一对应
 * @param dim      向量维度（BGE-M3 = 1024）。冗余字段，便于调用方早期断言
 * @param latencyMs 服务端从入参解析完到模型 inference 结束的耗时，单位毫秒
 */
public record EmbedResponse(
        List<float[]> vectors,
        int dim,
        long latencyMs) {}
