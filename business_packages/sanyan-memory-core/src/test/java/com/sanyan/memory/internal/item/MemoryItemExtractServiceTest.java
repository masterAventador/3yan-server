package com.sanyan.memory.internal.item;

import com.sanyan.llm.LlmApi;
import com.sanyan.llm.LlmTaskType;
import com.sanyan.llm.dto.ChatMessage;
import com.sanyan.memory.event.MemoryItemScheduledEvent;
import com.sanyan.memory.internal.item.fixtures.MemoryItemTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryItemExtractServiceTest {

    @Mock LlmApi llmApi;
    @Mock MemoryItemRepository repository;
    @Mock ApplicationEventPublisher events;

    // 固定时钟：2026-05-27T12:00:00Z（便于断言次日 9:00 等派生时间）
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-27T12:00:00Z"), ZoneId.of("UTC"));

    private MemoryItemExtractService service() {
        return new MemoryItemExtractService(llmApi, repository, events, clock);
    }

    private MemoryItemExtractService serviceWithClock(Clock fixed) {
        return new MemoryItemExtractService(llmApi, repository, events, fixed);
    }

    @Test
    void extract_should_inject_current_date_into_prompt() {
        // 固定时钟 2026-06-22（周一）
        Clock fixed = Clock.fixed(Instant.parse("2026-06-22T01:00:00Z"), ZoneId.of("Asia/Shanghai"));
        when(repository.findTop20ByUserIdAndCharacterIdAndStatusOrderByIdDesc(any(), any(), any()))
                .thenReturn(List.of());
        when(llmApi.chat(eq(LlmTaskType.BACKGROUND), anyList())).thenReturn("{\"items\":[]}");

        serviceWithClock(fixed).extract(1L, 1L, "周三有个面试好紧张", 100L);

        ArgumentCaptor<List<ChatMessage>> cap = ArgumentCaptor.forClass(List.class);
        verify(llmApi).chat(eq(LlmTaskType.BACKGROUND), cap.capture());
        String prompt = cap.getValue().stream().map(ChatMessage::content).reduce("", (a, b) -> a + "\n" + b);
        assertThat(prompt).contains("2026年6月22日");   // 当前日期注入
        assertThat(prompt).contains("周一");             // 周几，帮 LLM 推"周三"
    }

    @Test
    void extract_should_use_background_task_type_and_feed_existing_pending_items() {
        when(repository.findTop20ByUserIdAndCharacterIdAndStatusOrderByIdDesc(7L, 1L, MemoryItemStatus.PENDING))
                .thenReturn(List.of(withId(MemoryItemTestFixtures.emotion(7L, 1L, "最近压力大", Instant.now()), 55L)));
        when(llmApi.chat(eq(LlmTaskType.BACKGROUND), anyList()))
                .thenReturn("{\"items\":[]}");

        service().extract(7L, 1L, "今天面试好紧张", 1001L);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmApi).chat(eq(LlmTaskType.BACKGROUND), captor.capture());
        // prompt 里必须带上现有 PENDING 条目（去重上下文）+ 最新用户消息
        String joined = captor.getValue().stream().map(ChatMessage::content).reduce("", String::concat);
        assertThat(joined).contains("最近压力大");
        assertThat(joined).contains("今天面试好紧张");
    }

    @Test
    void extract_NEW_plan_event_should_save_pending_and_publish_event() {
        when(repository.findTop20ByUserIdAndCharacterIdAndStatusOrderByIdDesc(any(), any(), any()))
                .thenReturn(List.of());
        when(llmApi.chat(eq(LlmTaskType.BACKGROUND), anyList())).thenReturn("""
                {"items":[{"action":"NEW","kind":"PLAN_EVENT","content":"周三下午有面试","dateHint":"2026-06-03","targetId":null}]}
                """);
        when(repository.save(any())).thenAnswer(inv -> withId(inv.getArgument(0), 200L));

        service().extract(7L, 1L, "周三我有个面试", 1001L);

        ArgumentCaptor<MemoryItemEntity> saved = ArgumentCaptor.forClass(MemoryItemEntity.class);
        verify(repository).save(saved.capture());
        MemoryItemEntity e = saved.getValue();
        assertThat(e.getKind()).isEqualTo(MemoryItemKind.PLAN_EVENT);
        assertThat(e.getContent()).isEqualTo("周三下午有面试");
        assertThat(e.getStatus()).isEqualTo(MemoryItemStatus.PENDING);
        assertThat(e.getSourceMessageId()).isEqualTo(1001L);
        // PLAN_EVENT salient_at = 事件日 2026-06-03 当天 9:00（UTC）
        assertThat(e.getSalientAt()).isEqualTo(Instant.parse("2026-06-03T09:00:00Z"));

        ArgumentCaptor<MemoryItemScheduledEvent> evt = ArgumentCaptor.forClass(MemoryItemScheduledEvent.class);
        verify(events).publishEvent(evt.capture());
        assertThat(evt.getValue().memoryItemId()).isEqualTo(200L);
        assertThat(evt.getValue().kind()).isEqualTo("PLAN_EVENT");
        assertThat(evt.getValue().salientAt()).isEqualTo(Instant.parse("2026-06-03T09:00:00Z"));
    }

    @Test
    void extract_NEW_emotion_should_set_salient_next_day_9am_and_publish() {
        when(repository.findTop20ByUserIdAndCharacterIdAndStatusOrderByIdDesc(any(), any(), any()))
                .thenReturn(List.of());
        when(llmApi.chat(eq(LlmTaskType.BACKGROUND), anyList())).thenReturn("""
                {"items":[{"action":"NEW","kind":"EMOTION","content":"最近压力很大","dateHint":null,"targetId":null}]}
                """);
        when(repository.save(any())).thenAnswer(inv -> withId(inv.getArgument(0), 201L));

        service().extract(7L, 1L, "唉 最近压力好大", 1002L);

        ArgumentCaptor<MemoryItemEntity> saved = ArgumentCaptor.forClass(MemoryItemEntity.class);
        verify(repository).save(saved.capture());
        // EMOTION salient_at = 观察日(2026-05-27)次日 9:00 UTC = 2026-05-28T09:00:00Z
        assertThat(saved.getValue().getSalientAt()).isEqualTo(Instant.parse("2026-05-28T09:00:00Z"));
        verify(events, times(1)).publishEvent(any(MemoryItemScheduledEvent.class));
    }

    @Test
    void extract_UPDATE_should_modify_existing_content_and_not_publish_event() {
        MemoryItemEntity existing = withId(MemoryItemTestFixtures.emotion(7L, 1L, "压力大", Instant.now()), 55L);
        when(repository.findTop20ByUserIdAndCharacterIdAndStatusOrderByIdDesc(7L, 1L, MemoryItemStatus.PENDING))
                .thenReturn(List.of(existing));
        when(llmApi.chat(eq(LlmTaskType.BACKGROUND), anyList())).thenReturn("""
                {"items":[{"action":"UPDATE","kind":"EMOTION","content":"面试相关的焦虑、压力大","dateHint":null,"targetId":55}]}
                """);

        service().extract(7L, 1L, "面试好紧张", 1003L);

        ArgumentCaptor<MemoryItemEntity> saved = ArgumentCaptor.forClass(MemoryItemEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(55L);
        assertThat(saved.getValue().getContent()).isEqualTo("面试相关的焦虑、压力大");
        // UPDATE 不重新排期（已有条目已排过）
        verify(events, never()).publishEvent(any());
    }

    @Test
    void extract_UPDATE_targetId_not_in_user_pending_list_should_be_ignored() {
        // C1：LLM 幻觉出一个不属于本 user PENDING 列表的 targetId（如别人的条目 / DONE 条目）。
        // 必须按已加载的 existing 列表归属校验，命不中就忽略——杜绝跨用户篡改记忆。
        when(repository.findTop20ByUserIdAndCharacterIdAndStatusOrderByIdDesc(7L, 1L, MemoryItemStatus.PENDING))
                .thenReturn(List.of(withId(MemoryItemTestFixtures.emotion(7L, 1L, "压力大", Instant.now()), 55L)));
        when(llmApi.chat(eq(LlmTaskType.BACKGROUND), anyList())).thenReturn("""
                {"items":[{"action":"UPDATE","kind":"EMOTION","content":"偷改别人记忆","dateHint":null,"targetId":99999}]}
                """);

        service().extract(7L, 1L, "随便说一句", 1006L);

        // 不归属本用户的 targetId 一律不落库
        verify(repository, never()).save(any());
        verify(events, never()).publishEvent(any());
        // 绝不用 findById 越过归属边界查任意条目
        verify(repository, never()).findById(any());
    }

    @Test
    void extract_SKIP_should_not_save_nor_publish() {
        when(repository.findTop20ByUserIdAndCharacterIdAndStatusOrderByIdDesc(any(), any(), any()))
                .thenReturn(List.of(withId(MemoryItemTestFixtures.planEvent(7L, 1L, "周三面试", Instant.now()), 99L)));
        when(llmApi.chat(eq(LlmTaskType.BACKGROUND), anyList())).thenReturn("""
                {"items":[{"action":"SKIP","kind":"PLAN_EVENT","content":"","dateHint":null,"targetId":99}]}
                """);

        service().extract(7L, 1L, "对 就是周三那个面试", 1004L);

        verify(repository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void extract_NEW_promise_should_save_but_not_publish_event() {
        when(repository.findTop20ByUserIdAndCharacterIdAndStatusOrderByIdDesc(any(), any(), any()))
                .thenReturn(List.of());
        when(llmApi.chat(eq(LlmTaskType.BACKGROUND), anyList())).thenReturn("""
                {"items":[{"action":"NEW","kind":"PROMISE","content":"答应周末陪你看电影","dateHint":null,"targetId":null}]}
                """);
        when(repository.save(any())).thenAnswer(inv -> withId(inv.getArgument(0), 202L));

        service().extract(7L, 1L, "周末陪你看电影好不好", 1005L);

        verify(repository, times(1)).save(any());
        // PROMISE 本期仅留存，不发排期事件（spec §12）
        verify(events, never()).publishEvent(any());
    }

    @Test
    void extract_NEW_with_blank_content_should_be_skipped() {
        // m1：LLM 对 NEW 返回空 content；memory_item.content 是 NOT NULL，落库会抛约束异常。
        // 抽取链路必须在落库前丢弃空 content 条目。
        when(repository.findTop20ByUserIdAndCharacterIdAndStatusOrderByIdDesc(any(), any(), any()))
                .thenReturn(List.of());
        when(llmApi.chat(eq(LlmTaskType.BACKGROUND), anyList())).thenReturn("""
                {"items":[
                  {"action":"NEW","kind":"PLAN_EVENT","content":null,"dateHint":"2026-06-03","targetId":null},
                  {"action":"NEW","kind":"EMOTION","content":"   ","dateHint":null,"targetId":null}
                ]}
                """);

        service().extract(7L, 1L, "嗯", 1007L);

        verify(repository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    private static MemoryItemEntity withId(MemoryItemEntity e, long id) {
        e.setId(id);
        return e;
    }
}
