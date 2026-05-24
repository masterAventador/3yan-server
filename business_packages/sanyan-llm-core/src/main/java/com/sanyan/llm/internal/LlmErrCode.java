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
    LLM_PROVIDER_NOT_FOUND(4005, "找不到支持该任务类型的 LLM provider"),
    // 多个 provider 同时支持同一 LlmTaskType：fail-fast，强制 ops 修 application.yml 的
    // sanyan.<provider>.task-types 配置确保互斥（取代旧的 warn + 取首个，避免 classpath 顺序变化导致不稳定路由）
    LLM_PROVIDER_CONFLICT(4006, "LLM provider 配置冲突：多个 provider 同时支持该 task type，请检查 application.yml");

    private final int code;
    private final String defaultMessage;
}
