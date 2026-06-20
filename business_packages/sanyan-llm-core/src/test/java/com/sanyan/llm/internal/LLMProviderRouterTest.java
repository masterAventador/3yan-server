package com.sanyan.llm.internal;

import com.sanyan.common.error.BusinessException;
import com.sanyan.llm.LlmTaskType;
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
 *   <li>USER_FACING → 走 supports(USER_FACING) = true 的 provider</li>
 *   <li>BACKGROUND → 走 supports(BACKGROUND) = true 的 provider</li>
 *   <li>无 provider 匹配 → 抛 {@link BusinessException}(LLM_PROVIDER_NOT_FOUND)</li>
 *   <li>多个 provider 匹配 → 抛 {@link BusinessException}(LLM_PROVIDER_CONFLICT) fail-fast</li>
 *   <li>入参的 openAiMessages 原样透传给 provider（不再加工）</li>
 * </ul>
 * 具体哪个 provider 接哪种 task type 由 application.yml 的 {@code sanyan.<provider>.task-types}
 * 配置决定（豆包 / DeepSeek 等的归属不在路由层硬编码），因此测试用例名不再绑定 provider 名字。
 */
class LLMProviderRouterTest {

    @Test
    void chat_shouldRouteToProviderSupportingUserFacing() {
        LLMProvider userFacing = mock(LLMProvider.class);
        when(userFacing.supports(LlmTaskType.USER_FACING)).thenReturn(true);
        when(userFacing.supports(LlmTaskType.BACKGROUND)).thenReturn(false);
        when(userFacing.chat(any(), any())).thenReturn("user-reply");

        LLMProvider background = mock(LLMProvider.class);
        when(background.supports(LlmTaskType.USER_FACING)).thenReturn(false);
        when(background.supports(LlmTaskType.BACKGROUND)).thenReturn(true);

        LLMProviderRouter router = new LLMProviderRouter(List.of(userFacing, background));

        String reply = router.chat(LlmTaskType.USER_FACING, List.of(Map.of("role", "system", "content", "你是小婉")));

        assertThat(reply).isEqualTo("user-reply");
        verify(background, never()).chat(any(), any());
        verify(userFacing, times(1)).chat(any(), any());
    }

    @Test
    void chat_shouldRouteToProviderSupportingBackground() {
        LLMProvider userFacing = mock(LLMProvider.class);
        when(userFacing.supports(LlmTaskType.USER_FACING)).thenReturn(true);
        when(userFacing.supports(LlmTaskType.BACKGROUND)).thenReturn(false);

        LLMProvider background = mock(LLMProvider.class);
        when(background.supports(LlmTaskType.USER_FACING)).thenReturn(false);
        when(background.supports(LlmTaskType.BACKGROUND)).thenReturn(true);
        when(background.chat(any(), any())).thenReturn("bg-summary");

        LLMProviderRouter router = new LLMProviderRouter(List.of(userFacing, background));

        String reply = router.chat(LlmTaskType.BACKGROUND, List.of(Map.of("role", "system", "content", "你是摘要助手")));

        assertThat(reply).isEqualTo("bg-summary");
        verify(userFacing, never()).chat(any(), any());
        verify(background, times(1)).chat(any(), any());
    }

    @Test
    void chat_shouldPassMessagesThroughUnchanged() {
        LLMProvider provider = mock(LLMProvider.class);
        when(provider.supports(LlmTaskType.USER_FACING)).thenReturn(true);
        when(provider.chat(any(), any())).thenReturn("ok");

        LLMProviderRouter router = new LLMProviderRouter(List.of(provider));

        List<Map<String, String>> input = List.of(
                Map.of("role", "system", "content", "sys"),
                Map.of("role", "user", "content", "hello")
        );
        router.chat(LlmTaskType.USER_FACING, input);

        verify(provider).chat(LlmTaskType.USER_FACING, input);
    }

    @Test
    void chat_shouldThrowWhenNoProviderMatches() {
        LLMProvider only = mock(LLMProvider.class);
        when(only.supports(any())).thenReturn(false);

        LLMProviderRouter router = new LLMProviderRouter(List.of(only));

        assertThatThrownBy(() -> router.chat(LlmTaskType.USER_FACING, List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(LlmErrCode.LLM_PROVIDER_NOT_FOUND);
    }

    @Test
    void chat_shouldThrowWhenProvidersListEmpty() {
        LLMProviderRouter router = new LLMProviderRouter(List.of());

        assertThatThrownBy(() -> router.chat(LlmTaskType.BACKGROUND, List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(LlmErrCode.LLM_PROVIDER_NOT_FOUND);
    }

    @Test
    void chat_shouldThrowBusinessExceptionWhenMultipleProvidersSupport() {
        // 多个 provider 同时支持同一 task type → fail-fast，强制 ops 修配置确保互斥。
        // 取代原"warn + 取首个"逻辑（首个是按 Spring 注入顺序，classpath 变化时不稳定）。
        LLMProvider p1 = mock(LLMProvider.class);
        LLMProvider p2 = mock(LLMProvider.class);
        when(p1.supports(LlmTaskType.USER_FACING)).thenReturn(true);
        when(p2.supports(LlmTaskType.USER_FACING)).thenReturn(true);

        LLMProviderRouter router = new LLMProviderRouter(List.of(p1, p2));

        assertThatThrownBy(() -> router.chat(LlmTaskType.USER_FACING,
                List.of(Map.of("role", "system", "content", "x"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(LlmErrCode.LLM_PROVIDER_CONFLICT);

        verify(p1, never()).chat(any(), any());
        verify(p2, never()).chat(any(), any());
    }
}
