package com.sanyan.character.internal;

import com.sanyan.common.error.ErrCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CharacterErrCode implements ErrCode {
    CHARACTER_NOT_FOUND(3001, "角色不存在");

    private final int code;
    private final String defaultMessage;
}
