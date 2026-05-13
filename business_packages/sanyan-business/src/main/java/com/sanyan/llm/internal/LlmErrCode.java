package com.sanyan.llm.internal;

import com.sanyan.common.error.ErrCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum LlmErrCode implements ErrCode {
    LLM_CALL_FAILED(4001, "AI 服务暂时不可用");

    private final int code;
    private final String defaultMessage;
}
