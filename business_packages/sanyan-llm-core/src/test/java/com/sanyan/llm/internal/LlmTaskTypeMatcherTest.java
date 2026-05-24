package com.sanyan.llm.internal;

import com.sanyan.llm.LlmTaskType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LlmTaskTypeMatcher#parse(String)} 单元测试——覆盖所有 csv 解析边界。
 *
 * <p>parse 行为只在本类测试一次；DoubaoAdapterTest / DeepSeekAdapterTest 不重复测 parse，
 * 直接用 {@code ReflectionTestUtils.setField(adapter, "parsedTaskTypes", Set.of(...))}
 * 注入解析后的结果。
 */
class LlmTaskTypeMatcherTest {

    @Test
    void parse_shouldReturnEmptySetWhenInputIsNull() {
        assertThat(LlmTaskTypeMatcher.parse(null)).isEmpty();
    }

    @Test
    void parse_shouldReturnEmptySetWhenInputIsEmpty() {
        assertThat(LlmTaskTypeMatcher.parse("")).isEmpty();
    }

    @Test
    void parse_shouldReturnEmptySetWhenInputIsBlank() {
        assertThat(LlmTaskTypeMatcher.parse("   \t  ")).isEmpty();
    }

    @Test
    void parse_shouldReturnSingletonForSingleToken() {
        assertThat(LlmTaskTypeMatcher.parse("USER_FACING"))
                .containsExactly(LlmTaskType.USER_FACING);
    }

    @Test
    void parse_shouldReturnAllConfiguredTypes() {
        assertThat(LlmTaskTypeMatcher.parse("USER_FACING,BACKGROUND"))
                .containsExactlyInAnyOrder(LlmTaskType.USER_FACING, LlmTaskType.BACKGROUND);
    }

    @Test
    void parse_shouldTolerateWhitespaceAndCase() {
        assertThat(LlmTaskTypeMatcher.parse("  user_facing , BACKGROUND  "))
                .containsExactlyInAnyOrder(LlmTaskType.USER_FACING, LlmTaskType.BACKGROUND);
    }

    @Test
    void parse_shouldDeduplicateRepeatedTokens() {
        assertThat(LlmTaskTypeMatcher.parse("USER_FACING,USER_FACING,user_facing"))
                .containsExactly(LlmTaskType.USER_FACING);
    }

    @Test
    void parse_shouldIgnoreTrailingComma() {
        assertThat(LlmTaskTypeMatcher.parse("USER_FACING,"))
                .containsExactly(LlmTaskType.USER_FACING);
    }

    @Test
    void parse_shouldIgnoreEmptyTokensInMiddle() {
        assertThat(LlmTaskTypeMatcher.parse("USER_FACING,,BACKGROUND"))
                .containsExactlyInAnyOrder(LlmTaskType.USER_FACING, LlmTaskType.BACKGROUND);
    }

    @Test
    void parse_shouldThrowOnUnknownToken() {
        assertThatThrownBy(() -> LlmTaskTypeMatcher.parse("USER_FACING,FOO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FOO")
                .hasMessageContaining("Unknown LlmTaskType");
    }

    @Test
    void parse_shouldReturnUnmodifiableSet() {
        Set<LlmTaskType> result = LlmTaskTypeMatcher.parse("USER_FACING");
        assertThatThrownBy(() -> result.add(LlmTaskType.BACKGROUND))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
