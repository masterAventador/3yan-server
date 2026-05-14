package com.sanyan.llm.internal;

import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.SenderType;
import com.sanyan.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * Task M3：{@link LLMProviderRouter} 单元测试。
 *
 * <p>测试用 mock {@link LLMProvider} 验证按 {@link LLMTaskType} 路由的逻辑：
 * <ul>
 *   <li>USER_FACING → 走 supports(USER_FACING) = true 的 provider（豆包）</li>
 *   <li>BACKGROUND → 走 supports(BACKGROUND) = true 的 provider（DeepSeek）</li>
 *   <li>无 provider 匹配 → 抛 {@link BusinessException}(LLM_PROVIDER_NOT_FOUND)</li>
 *   <li>多个 provider 匹配 → 取首个，并 warn 日志</li>
 *   <li>chat 入参的 systemPrompt + messages 必须正确组装成 OpenAI 兼容格式</li>
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

        String reply = router.chat(LLMTaskType.USER_FACING, "你是小婉", List.of());

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

        String reply = router.chat(LLMTaskType.BACKGROUND, "你是摘要助手", List.of());

        assertThat(reply).isEqualTo("bg-summary");
        verify(userFacing, never()).chat(any());
        verify(background, times(1)).chat(any());
    }

    @Test
    void chat_shouldBuildOpenAiMessagesFromSystemPromptAndMessageEntities() {
        LLMProvider provider = mock(LLMProvider.class);
        when(provider.supports(LLMTaskType.USER_FACING)).thenReturn(true);
        when(provider.chat(any())).thenReturn("ok");

        LLMProviderRouter router = new LLMProviderRouter(List.of(provider));

        MessageEntity userMsg = new MessageEntity();
        userMsg.setSenderType(SenderType.USER);
        userMsg.setContent("早上好");
        MessageEntity aiMsg = new MessageEntity();
        aiMsg.setSenderType(SenderType.AI);
        aiMsg.setContent("早上好呀");

        router.chat(LLMTaskType.USER_FACING, "你是小婉", List.of(userMsg, aiMsg));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider).chat(captor.capture());
        List<Map<String, String>> sent = captor.getValue();

        assertThat(sent).hasSize(3);
        assertThat(sent.get(0)).containsEntry("role", "system").containsEntry("content", "你是小婉");
        assertThat(sent.get(1)).containsEntry("role", "user").containsEntry("content", "早上好");
        assertThat(sent.get(2)).containsEntry("role", "assistant").containsEntry("content", "早上好呀");
    }

    @Test
    void chat_shouldThrowWhenNoProviderMatches() {
        LLMProvider only = mock(LLMProvider.class);
        when(only.supports(any())).thenReturn(false);

        LLMProviderRouter router = new LLMProviderRouter(List.of(only));

        assertThatThrownBy(() -> router.chat(LLMTaskType.USER_FACING, "x", List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrCode())
                .isEqualTo(LlmErrCode.LLM_PROVIDER_NOT_FOUND);
    }

    @Test
    void chat_shouldThrowWhenProvidersListEmpty() {
        LLMProviderRouter router = new LLMProviderRouter(List.of());

        assertThatThrownBy(() -> router.chat(LLMTaskType.BACKGROUND, "x", List.of()))
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

        String reply = router.chat(LLMTaskType.USER_FACING, "sys", List.of());

        assertThat(reply).isEqualTo("first-wins");
        verify(second, never()).chat(any());
    }

    @Test
    void chat_shouldHandleNullMessageContentSafely() {
        LLMProvider provider = mock(LLMProvider.class);
        when(provider.supports(LLMTaskType.USER_FACING)).thenReturn(true);
        when(provider.chat(any())).thenReturn("ok");

        LLMProviderRouter router = new LLMProviderRouter(List.of(provider));

        MessageEntity nullContent = new MessageEntity();
        nullContent.setSenderType(SenderType.USER);
        nullContent.setContent(null);

        router.chat(LLMTaskType.USER_FACING, "sys", List.of(nullContent));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider).chat(captor.capture());
        List<Map<String, String>> sent = captor.getValue();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).containsEntry("role", "user").containsEntry("content", "");
    }
}
