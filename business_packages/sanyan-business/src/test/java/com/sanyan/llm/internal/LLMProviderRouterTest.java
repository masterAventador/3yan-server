package com.sanyan.llm.internal;

import com.sanyan.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task M3：{@link LLMProviderRouter} 单元测试（Q3 简化）。
 *
 * <p>Q3 task 把 OpenAI 消息拼装从 router 抽出到 {@link PromptBuilder}，router 退化为
 * 纯路由层。本测试只验证路由逻辑：
 * <ul>
 *   <li>USER_FACING → 走 supports(USER_FACING) = true 的 provider（豆包）</li>
 *   <li>BACKGROUND → 走 supports(BACKGROUND) = true 的 provider（DeepSeek）</li>
 *   <li>无 provider 匹配 → 抛 {@link BusinessException}(LLM_PROVIDER_NOT_FOUND)</li>
 *   <li>多个 provider 匹配 → 取首个</li>
 *   <li>入参的 openAiMessages 原样透传给 provider（不再加工）</li>
 * </ul>
 */
class LLMProviderRouterTest {

    @Test
    void chat_shouldRouteUserFacingToDoubaoProvider() {
        LLMProvider userFacing = mock(LLMProvider.class);
        when(userFacing.supports(LLMTaskType.USER_FACING)).thenReturn(true);
        when(userFacing.supports(LLMTaskType.BACKGROUND)).thenReturn(false);
        when(userFacing.chat(any())).thenReturn("user-reply");

        LLMProvider background = mock(LLMProvider.class);
        when(background.supports(LLMTaskType.USER_FACING)).thenReturn(false);
        when(background.supports(LLMTaskType.BACKGROUND)).thenReturn(true);

        LLMProviderRouter router = new LLMProviderRouter(List.of(userFacing, background));

        String reply = router.chat(LLMTaskType.USER_FACING, List.of(Map.of("role", "system", "content", "你是小婉")));

        assertThat(reply).isEqualTo("user-reply");
        verify(background, never()).chat(any());
        verify(userFacing, times(1)).chat(any());
    }

    @Test
    void chat_shouldRouteBackgroundToDeepSeekProvider() {
        LLMProvider userFacing = mock(LLMProvider.class);
        when(userFacing.supports(LLMTaskType.USER_FACING)).thenReturn(true);
        when(userFacing.supports(LLMTaskType.BACKGROUND)).thenReturn(false);

        LLMProvider background = mock(LLMProvider.class);
        when(background.supports(LLMTaskType.USER_FACING)).thenReturn(false);
        when(background.supports(LLMTaskType.BACKGROUND)).thenReturn(true);
        when(background.chat(any())).thenReturn("bg-summary");

        LLMProviderRouter router = new LLMProviderRouter(List.of(userFacing, background));

        String reply = router.chat(LLMTaskType.BACKGROUND, List.of(Map.of("role", "system", "content", "你是摘要助手")));

        assertThat(reply).isEqualTo("bg-summary");
        verify(userFacing, never()).chat(any());
        verify(background, times(1)).chat(any());
    }

    @Test
    void chat_shouldPassMessagesThroughUnchanged() {
        LLMProvider provider = mock(LLMProvider.class);
        when(provider.supports(LLMTaskType.USER_FACING)).thenReturn(true);
        when(provider.chat(any())).thenReturn("ok");

        LLMProviderRouter router = new LLMProviderRouter(List.of(provider));

        List<Map<String, String>> input = List.of(
                Map.of("role", "system", "content", "sys"),
                Map.of("role", "user", "content", "hello")
        );
        router.chat(LLMTaskType.USER_FACING, input);

        verify(provider).chat(input);
    }

    @Test
    void chat_shouldThrowWhenNoProviderMatches() {
        LLMProvider only = mock(LLMProvider.class);
        when(only.supports(any())).thenReturn(false);

        LLMProviderRouter router = new LLMProviderRouter(List.of(only));

        assertThatThrownBy(() -> router.chat(LLMTaskType.USER_FACING, List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(LlmErrCode.LLM_PROVIDER_NOT_FOUND);
    }

    @Test
    void chat_shouldThrowWhenProvidersListEmpty() {
        LLMProviderRouter router = new LLMProviderRouter(List.of());

        assertThatThrownBy(() -> router.chat(LLMTaskType.BACKGROUND, List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(LlmErrCode.LLM_PROVIDER_NOT_FOUND);
    }

    @Test
    void chat_shouldPickFirstWhenMultipleProvidersMatch() {
        LLMProvider first = mock(LLMProvider.class);
        when(first.supports(LLMTaskType.USER_FACING)).thenReturn(true);
        when(first.chat(any())).thenReturn("first-wins");

        LLMProvider second = mock(LLMProvider.class);
        when(second.supports(LLMTaskType.USER_FACING)).thenReturn(true);

        LLMProviderRouter router = new LLMProviderRouter(List.of(first, second));

        String reply = router.chat(LLMTaskType.USER_FACING, List.of());

        assertThat(reply).isEqualTo("first-wins");
        verify(second, never()).chat(any());
    }
}
