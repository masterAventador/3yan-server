package com.sanyan.embedding.internal;

import com.sanyan.common.error.ErrCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Embedding 服务错误码（6000-6999 区间，Plan 2 错误码登记表里专给本服务）。
 *
 * <p>注意：本服务部署在 old，独立 Spring Boot context，错误码区间与主服务（new）互不冲突。
 * 主服务侧的 {@code MemoryErrCode.EMBEDDING_SERVICE_UNAVAILABLE} 在主服务区间内独立分配，
 * 与本枚举无重叠（Task M2c 落实）。
 */
@Getter
@AllArgsConstructor
public enum EmbeddingErrCode implements ErrCode {
    /** Adapter 还在加载模型 / 加载失败，未达到 ready 状态。 */
    MODEL_NOT_READY(6001, "embedding 模型未加载完成"),
    /** 模型推理过程异常（IO / runtime）。 */
    EMBEDDING_FAILED(6003, "Embedding 推理失败");

    private final int code;
    private final String defaultMessage;
}
