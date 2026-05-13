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
    LLM_UPSTREAM_UNAVAILABLE(4003, "AI 服务暂时不可用");

    private final int code;
    private final String defaultMessage;
}
