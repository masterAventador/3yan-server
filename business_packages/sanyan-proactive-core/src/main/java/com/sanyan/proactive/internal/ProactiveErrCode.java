package com.sanyan.proactive.internal;

import com.sanyan.common.error.ErrCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 主动消息域错误码（7000-7999，见 ERROR_CODE_REGISTRY）。 */
@Getter
@AllArgsConstructor
public enum ProactiveErrCode implements ErrCode {
    PROACTIVE_GENERATE_FAILED(7001, "主动消息生成失败"),
    PROACTIVE_EVENT_TYPE_INVALID(7002, "主动事件类型不合法"),
    /**
     * 主动事件关联的记忆条目不存在。
     *
     * <p><b>生产路径说明：</b>当前 {@link com.sanyan.proactive.internal.generator.EventFollowupGenerator}
     * 和 {@link com.sanyan.proactive.internal.generator.EmotionCareGenerator} 调用
     * {@code memoryApi.getMemoryItem} 查不到条目时，memory 域实际抛出的是
     * {@code BusinessException(MemoryErrCode.MEMORY_ITEM_NOT_FOUND, code=5003)}，
     * 两个 Generator 的 {@code catch (BusinessException)} 会静默降级返回空列表，
     * 此 7003 不会出现在生产日志中。
     *
     * <p>7003 为测试用例 / 未来触发器精细处理（如需区分 proactive 域的"找不到"与 memory 域的"找不到"）预留。
     */
    PROACTIVE_EVENT_NOT_FOUND(7003, "主动事件关联的记忆条目不存在");

    private final int code;
    private final String defaultMessage;
}
