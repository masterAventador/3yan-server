package com.sanyan.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ErrCodeConflictDetectorTest {

    enum ErrA implements ErrCode {
        A_ONE;
        @Override public int getCode() { return 100; }
        @Override public String getDefaultMessage() { return "a"; }
    }
    enum ErrB implements ErrCode {
        B_ONE;
        @Override public int getCode() { return 100; }
        @Override public String getDefaultMessage() { return "b"; }
    }
    enum ErrC implements ErrCode {
        C_ONE;
        @Override public int getCode() { return 200; }
        @Override public String getDefaultMessage() { return "c"; }
    }

    @Test
    void detect_noConflict_passes() {
        new ErrCodeConflictDetector(java.util.List.of(ErrA.class, ErrC.class))
            .run((ApplicationArguments) null);
    }

    @Test
    void detect_conflict_throws() {
        assertThatThrownBy(() ->
            new ErrCodeConflictDetector(java.util.List.of(ErrA.class, ErrB.class))
                .run((ApplicationArguments) null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ErrCode 冲突")
            .hasMessageContaining("100");
    }
}
