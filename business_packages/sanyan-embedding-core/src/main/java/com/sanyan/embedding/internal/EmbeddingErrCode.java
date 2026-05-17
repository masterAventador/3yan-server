package com.sanyan.embedding.internal;

import com.sanyan.common.error.ErrCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Embedding 域错误码。占用 6xxx 区间（详见 ERROR_CODE_REGISTRY.md）。
 *
 * <p>历史：S3 Phase 4 之前，embedding 失败用 {@code LlmErrCode.EMBEDDING_SERVICE_UNAVAILABLE}（4004）。
 * 拆 embedding 独立域后，错误码迁到本 enum，新 code 6001。
 *
 * <p>注：{@code MemoryErrCode.EMBEDDING_SERVICE_UNAVAILABLE}（5002）保留不变——那是
 * memory 业务层视角的"embedding 不可用"信号（用于触发降级），跟本 6001（HTTP 客户端层
 * 实际抛的"硅基流动不可达"）不同抽象层级。
 */
@Getter
@AllArgsConstructor
public enum EmbeddingErrCode implements ErrCode {
    // 协议级失败：4xx 不重试 / 5xx 重试耗尽 / 网络异常
    EMBEDDING_SERVICE_UNAVAILABLE(6001, "Embedding 服务不可用");

    private final int code;
    private final String defaultMessage;
}
