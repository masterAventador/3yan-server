package com.sanyan.llm.internal;

import com.sanyan.common.error.ErrCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum LlmErrCode implements ErrCode {
    LLM_CALL_FAILED(4001, "AI 服务暂时不可用"),
    // 上游 LLM 4xx：鉴权失败 / 限流 / 请求非法（不应重试）
    LLM_UPSTREAM_4XX(4002, "AI 服务请求被拒绝"),
    // 上游 LLM 5xx / timeout / 网络不通（M2c task 才加重试，本 task 直接抛）
    LLM_UPSTREAM_UNAVAILABLE(4003, "AI 服务暂时不可用"),
    // M3：LLMProviderRouter 找不到支持给定 LlmTaskType 的 provider（装配遗漏或配置缺失）
    // 注：4004 EMBEDDING_SERVICE_UNAVAILABLE 已于 S3 Phase 4 迁到 EmbeddingErrCode 6001，不再占用。
    LLM_PROVIDER_NOT_FOUND(4005, "找不到支持该任务类型的 LLM provider");

    private final int code;
    private final String defaultMessage;
}
