package com.sanyan.memory.internal.profile;

import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.SenderType;
import com.sanyan.common.error.BusinessException;
import com.sanyan.llm.internal.LLMProviderRouter;
import com.sanyan.llm.internal.LLMTaskType;
import com.sanyan.llm.internal.LlmErrCode;
import com.sanyan.memory.internal.MemoryErrCode;
import com.sanyan.memory.internal.profile.fixtures.MemoryProfileTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan 2.5：{@link MemoryProfileRefreshService} Mockito 单测。
 *
 * <p>覆盖核心场景：
 * <ul>
 *   <li>正常路径：LLM 返回新画像段落 → 写回 entity</li>
 *   <li>首次刷新：profile 不存在 → 创建初始 entity + LLM 看到 "（暂无）" 占位</li>
 *   <li>LLM 返回 blank → 不写回 + 返回 null（不抛异常）</li>
 *   <li>LLM 抛 BusinessException → 吞掉 + 返回 null</li>
 *   <li>recentMessages 为 null / 空 → 直接返回 null（不调 LLM）</li>
 *   <li>LLM 任务类型为 BACKGROUND（后台异步走低成本模型）</li>
 *   <li>system prompt 包含关键文本（守护模板）</li>
 *   <li>乐观锁首次冲突重试一次成功</li>
 *   <li>乐观锁两次冲突 → 抛 BusinessException(PROFILE_REFRESH_CONFLICT)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MemoryProfileRefreshServiceTest {

    @Mock
    private MemoryProfileRepository repository;

    @Mock
    private LLMProviderRouter llmRouter;

    private MemoryProfileRefreshService newService() {
        return new MemoryProfileRefreshService(repository, llmRouter);
    }

    // ============ 正常路径 ============

    @Test
    void refresh_shouldUpdateSummaryTextWithLlmOutput() {
        MemoryProfileRefreshService service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.profileWithSummary("旧画像");
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(MemoryProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(llmRouter.chat(eq(LLMTaskType.BACKGROUND), any())).thenReturn("用户名叫张三，前端工程师。");

        MemoryProfileEntity result = service.refresh(1L, 1L, buildMessages(3));

        assertThat(result).isNotNull();
        assertThat(result.getSummaryText())
                .as("entity 的 summaryText 应被替换为 LLM 输出（trim 掉首尾空白）")
                .isEqualTo("用户名叫张三，前端工程师。");
    }

    @Test
    void refresh_shouldTrimLlmOutput() {
        MemoryProfileRefreshService service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.profileWithSummary("旧画像");
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(MemoryProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // LLM 输出常带前后换行 / 空格，service 应 trim 后落库
        when(llmRouter.chat(eq(LLMTaskType.BACKGROUND), any()))
                .thenReturn("\n\n  用户喜欢猫。  \n");

        MemoryProfileEntity result = service.refresh(1L, 1L, buildMessages(3));

        assertThat(result.getSummaryText()).isEqualTo("用户喜欢猫。");
    }

    // ============ 首次刷新 ============

    @Test
    void refresh_shouldCreateInitialProfileWhenNotExists() {
        MemoryProfileRefreshService service = newService();
        when(repository.findByUserIdAndCharacterId(2L, 3L)).thenReturn(Optional.empty());
        when(repository.save(any(MemoryProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(llmRouter.chat(eq(LLMTaskType.BACKGROUND), any())).thenReturn("首次画像。");

        MemoryProfileEntity result = service.refresh(2L, 3L, buildMessages(3));

        assertThat(result.getUserId()).isEqualTo(2L);
        assertThat(result.getCharacterId()).isEqualTo(3L);
        assertThat(result.getSummaryText()).isEqualTo("首次画像。");
    }

    @Test
    void refresh_shouldFeedPlaceholderWhenCurrentSummaryIsBlank() {
        MemoryProfileRefreshService service = newService();
        when(repository.findByUserIdAndCharacterId(2L, 3L)).thenReturn(Optional.empty());
        when(repository.save(any(MemoryProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(llmRouter.chat(eq(LLMTaskType.BACKGROUND), any())).thenReturn("画像内容");

        service.refresh(2L, 3L, buildMessages(3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmRouter).chat(eq(LLMTaskType.BACKGROUND), captor.capture());
        String prompt = captor.getValue().get(0).get("content");
        assertThat(prompt)
                .as("现有画像为空时，prompt 必须用 (暂无) 占位避免 LLM 看到孤零零的'现有画像：'")
                .contains("（暂无）");
    }

    // ============ 容错 ============

    @Test
    void refresh_shouldReturnNullWhenLlmReturnsBlank() {
        MemoryProfileRefreshService service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.profileWithSummary("旧画像");
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(llmRouter.chat(eq(LLMTaskType.BACKGROUND), any())).thenReturn("   \n  ");

        MemoryProfileEntity result = service.refresh(1L, 1L, buildMessages(3));

        assertThat(result).as("LLM 返回 blank 应返回 null").isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void refresh_shouldReturnNullWhenLlmReturnsNull() {
        MemoryProfileRefreshService service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.profileWithSummary("旧画像");
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(llmRouter.chat(eq(LLMTaskType.BACKGROUND), any())).thenReturn(null);

        MemoryProfileEntity result = service.refresh(1L, 1L, buildMessages(3));

        assertThat(result).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void refresh_shouldReturnNullWhenLlmThrowsBusinessException() {
        MemoryProfileRefreshService service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.profileWithSummary("旧画像");
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(llmRouter.chat(eq(LLMTaskType.BACKGROUND), any()))
                .thenThrow(new BusinessException(LlmErrCode.LLM_UPSTREAM_UNAVAILABLE));

        MemoryProfileEntity result = service.refresh(1L, 1L, buildMessages(3));

        assertThat(result)
                .as("LLM 上游异常时 service 必须吞掉返回 null（容错：让 listener 跳过即可）")
                .isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void refresh_shouldReturnNullForEmptyOrNullMessages() {
        MemoryProfileRefreshService service = newService();

        assertThat(service.refresh(1L, 1L, null)).as("null 输入 → null").isNull();
        assertThat(service.refresh(1L, 1L, List.of())).as("空列表输入 → null").isNull();
        verify(repository, never()).findByUserIdAndCharacterId(any(), any());
        verify(llmRouter, never()).chat(any(), any());
    }

    // ============ LLM 路由 / prompt 守护 ============

    @Test
    void refresh_shouldRouteToBackgroundTaskType() {
        MemoryProfileRefreshService service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.profileWithSummary("旧画像");
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(MemoryProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(llmRouter.chat(any(LLMTaskType.class), any())).thenReturn("new summary");

        service.refresh(1L, 1L, buildMessages(3));

        ArgumentCaptor<LLMTaskType> taskTypeCaptor = ArgumentCaptor.forClass(LLMTaskType.class);
        verify(llmRouter).chat(taskTypeCaptor.capture(), any());
        assertThat(taskTypeCaptor.getValue())
                .as("画像刷新属于后台任务，必须路由到 BACKGROUND（低成本模型）")
                .isEqualTo(LLMTaskType.BACKGROUND);
    }

    @Test
    void refresh_systemPromptShouldContainCurrentSummaryAndRecentMessages() {
        MemoryProfileRefreshService service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.profileWithSummary("张三是工程师");
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(MemoryProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(llmRouter.chat(any(LLMTaskType.class), any())).thenReturn("new");

        List<MessageEntity> messages = buildMessages(2);
        messages.get(0).setContent("你好啊");
        messages.get(1).setContent("好呀，吃了吗");
        service.refresh(1L, 1L, messages);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmRouter).chat(eq(LLMTaskType.BACKGROUND), captor.capture());
        String prompt = captor.getValue().get(0).get("content");

        assertThat(prompt).as("prompt 必须包含现有画像").contains("张三是工程师");
        assertThat(prompt).as("prompt 必须包含最近对话内容").contains("你好啊").contains("好呀，吃了吗");
        assertThat(prompt).as("prompt 必须含'直接输出'约束防止 LLM 加前缀").contains("直接输出");
    }

    // ============ 乐观锁 ============

    @Test
    void refresh_shouldRetryOnceOnOptimisticLockConflictAndSucceed() {
        MemoryProfileRefreshService service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.profileWithSummary("旧画像");
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(llmRouter.chat(eq(LLMTaskType.BACKGROUND), any())).thenReturn("new summary");
        when(repository.save(any(MemoryProfileEntity.class)))
                .thenThrow(new OptimisticLockingFailureException("conflict 1"))
                .thenAnswer(inv -> inv.getArgument(0));

        MemoryProfileEntity result = service.refresh(1L, 1L, buildMessages(3));

        assertThat(result).isNotNull();
        verify(repository, times(2)).findByUserIdAndCharacterId(1L, 1L);
        verify(repository, times(2)).save(any(MemoryProfileEntity.class));
    }

    @Test
    void refresh_shouldThrowBusinessExceptionAfterRetryFailure() {
        MemoryProfileRefreshService service = newService();
        MemoryProfileEntity existing = MemoryProfileTestFixtures.profileWithSummary("旧画像");
        when(repository.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(existing));
        when(llmRouter.chat(eq(LLMTaskType.BACKGROUND), any())).thenReturn("new summary");
        when(repository.save(any(MemoryProfileEntity.class)))
                .thenThrow(new OptimisticLockingFailureException("conflict 1"))
                .thenThrow(new OptimisticLockingFailureException("conflict 2"));

        assertThatThrownBy(() -> service.refresh(1L, 1L, buildMessages(3)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrCode())
                        .isEqualTo(MemoryErrCode.PROFILE_REFRESH_CONFLICT));
        verify(repository, times(2)).save(any(MemoryProfileEntity.class));
    }

    // ============ helpers ============

    private static List<MessageEntity> buildMessages(int count) {
        List<MessageEntity> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MessageEntity m = new MessageEntity();
            m.setUserId(1L);
            m.setSenderType(i % 2 == 0 ? SenderType.USER : SenderType.AI);
            m.setContent(i % 2 == 0 ? "用户消息 " + i : "AI 回复 " + i);
            messages.add(m);
        }
        return messages;
    }
}
