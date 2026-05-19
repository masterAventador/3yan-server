package com.sanyan.character.internal;

import com.sanyan.common.error.ErrCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CharacterErrCode implements ErrCode {
    CHARACTER_NOT_FOUND(3001, "角色不存在"),
    RELATIONSHIP_NOT_FOUND(3002, "关系不存在"),
    INTIMACY_CONCURRENT_UPDATE(3003, "亲密度并发更新失败");

    private final int code;
    private final String defaultMessage;
}
