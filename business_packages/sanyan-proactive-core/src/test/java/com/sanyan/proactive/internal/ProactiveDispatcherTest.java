package com.sanyan.proactive.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanyan.character.CharacterApi;
import com.sanyan.character.dto.RelationshipDto;
import com.sanyan.chat.ChatApi;
import com.sanyan.chat.dto.MessageDto;
import com.sanyan.memory.MemoryApi;
import com.sanyan.memory.dto.MemoryContext;
import com.sanyan.proactive.internal.fixtures.EventPendingTestFixtures;
import com.sanyan.proactive.internal.generator.GenerateContext;
import com.sanyan.proactive.internal.generator.ProactiveGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProactiveDispatcherTest {

    @Mock CharacterApi characterApi;
    @Mock MemoryApi memoryApi;
    @Mock ChatApi chatApi;
    @Mock FrequencyGate frequencyGate;
    @Mock ProactiveGenerator greetingGenerator;
    @Mock EventPendingRepository repo;

    private RelationshipDto relAtStage(int stage) {
        return new RelationshipDto(1L, 99L, 350, stage, "暧昧", 600, 0.16);
    }

    private ProactiveDispatcher newDispatcher() {
        return new ProactiveDispatcher(
                characterApi, memoryApi, chatApi, frequencyGate,
                List.of(greetingGenerator), new ObjectMapper(), repo);
    }

    @Test
    void dispatch_blocked_by_gate_should_mark_cancelled_save_and_not_generate() {
        when(characterApi.findOrCreateRelationship(1L, 99L)).thenReturn(relAtStage(2));
        when(frequencyGate.allow(1L, 99L, EventType.A_GREETING, 2)).thenReturn(false);

        EventPendingEntity event = EventPendingTestFixtures.scheduled(
                1L, 99L, EventType.A_GREETING, Instant.now());

        newDispatcher().dispatch(event);

        assertThat(event.getStatus()).isEqualTo(EventStatus.CANCELLED);
        verify(repo).save(event);
        verify(greetingGenerator, never()).generate(any());
        verify(chatApi, never()).deliverProactiveMessage(anyLong(), anyLong(), any());
    }

    @Test
    void dispatch_allowed_should_generate_deliver_mark_sent_save_and_record() {
        when(characterApi.findOrCreateRelationship(1L, 99L)).thenReturn(relAtStage(2));
        when(frequencyGate.allow(1L, 99L, EventType.A_GREETING, 2)).thenReturn(true);
        when(characterApi.getStagePromptSegment(1L, 99L)).thenReturn("当前关系阶段：暧昧。");
        when(memoryApi.getRelevantContext(eq(1L), eq(99L), anyString())).thenReturn(new MemoryContext("记忆片段"));
        when(greetingGenerator.supportsType()).thenReturn(EventType.A_GREETING);
        when(greetingGenerator.generate(any(GenerateContext.class))).thenReturn(List.of("早安宝", "今天也要开心"));
        when(chatApi.listRecentProactive(eq(1L), anyInt())).thenReturn(List.of());

        EventPendingEntity event = EventPendingTestFixtures.scheduled(
                1L, 99L, EventType.A_GREETING, Instant.now());

        newDispatcher().dispatch(event);

        assertThat(event.getStatus()).isEqualTo(EventStatus.SENT);
        assertThat(event.getSentAt()).isNotNull();
        verify(repo).save(event);
        verify(chatApi).deliverProactiveMessage(1L, 99L, List.of("早安宝", "今天也要开心"));
        verify(frequencyGate).recordSent(1L);
        // a 类不标记 memory item done
        verify(memoryApi, never()).markMemoryItemDone(anyLong());
    }

    @Test
    void dispatch_recordSent_throwing_should_keep_sent_not_backoff() {
        when(characterApi.findOrCreateRelationship(1L, 99L)).thenReturn(relAtStage(2));
        when(frequencyGate.allow(1L, 99L, EventType.A_GREETING, 2)).thenReturn(true);
        when(characterApi.getStagePromptSegment(1L, 99L)).thenReturn("当前关系阶段：暧昧。");
        when(memoryApi.getRelevantContext(eq(1L), eq(99L), anyString())).thenReturn(new MemoryContext("记忆片段"));
        when(greetingGenerator.supportsType()).thenReturn(EventType.A_GREETING);
        when(greetingGenerator.generate(any(GenerateContext.class))).thenReturn(List.of("早安宝"));
        when(chatApi.listRecentProactive(eq(1L), anyInt())).thenReturn(List.of());
        // 消息已投出，recordSent（Redis）抛异常 —— best-effort，不得把 SENT 覆盖回 SCHEDULED 触发重投
        org.mockito.Mockito.doThrow(new RuntimeException("redis down")).when(frequencyGate).recordSent(1L);

        EventPendingEntity event = EventPendingTestFixtures.scheduled(
                1L, 99L, EventType.A_GREETING, Instant.now());
        event.setFailCount(0);

        newDispatcher().dispatch(event);

        assertThat(event.getStatus()).isEqualTo(EventStatus.SENT);
        assertThat(event.getFailCount()).isEqualTo(0); // 不退避
        verify(chatApi).deliverProactiveMessage(1L, 99L, List.of("早安宝"));
        verify(repo).save(event);
    }

    @Test
    void dispatch_markMemoryItemDone_throwing_should_keep_sent_not_backoff() {
        ProactiveGenerator followup = org.mockito.Mockito.mock(ProactiveGenerator.class);
        when(followup.supportsType()).thenReturn(EventType.C_EVENT_FOLLOWUP);
        when(followup.generate(any())).thenReturn(List.of("面试怎么样啦"));
        when(characterApi.findOrCreateRelationship(1L, 99L)).thenReturn(relAtStage(2));
        when(frequencyGate.allow(1L, 99L, EventType.C_EVENT_FOLLOWUP, 2)).thenReturn(true);
        when(characterApi.getStagePromptSegment(1L, 99L)).thenReturn("");
        when(memoryApi.getRelevantContext(anyLong(), anyLong(), anyString())).thenReturn(null);
        when(chatApi.listRecentProactive(eq(1L), anyInt())).thenReturn(List.of());
        // 消息已投出，markMemoryItemDone 抛异常 —— best-effort，不得覆盖 SENT
        org.mockito.Mockito.doThrow(new RuntimeException("mark fail")).when(memoryApi).markMemoryItemDone(555L);

        ProactiveDispatcher dispatcher = new ProactiveDispatcher(
                characterApi, memoryApi, chatApi, frequencyGate, List.of(followup), new ObjectMapper(), repo);

        EventPendingEntity event = EventPendingTestFixtures.withPayload(
                1L, 99L, EventType.C_EVENT_FOLLOWUP, Instant.now(), "{\"memoryItemId\": 555}");

        dispatcher.dispatch(event);

        assertThat(event.getStatus()).isEqualTo(EventStatus.SENT);
        assertThat(event.getFailCount()).isEqualTo(0);
        verify(repo).save(event);
    }

    @Test
    void dispatch_event_followup_should_mark_memory_item_done() {
        ProactiveGenerator followup = org.mockito.Mockito.mock(ProactiveGenerator.class);
        when(followup.supportsType()).thenReturn(EventType.C_EVENT_FOLLOWUP);
        when(followup.generate(any())).thenReturn(List.of("面试怎么样啦"));
        when(characterApi.findOrCreateRelationship(1L, 99L)).thenReturn(relAtStage(2));
        when(frequencyGate.allow(1L, 99L, EventType.C_EVENT_FOLLOWUP, 2)).thenReturn(true);
        when(characterApi.getStagePromptSegment(1L, 99L)).thenReturn("");
        when(memoryApi.getRelevantContext(anyLong(), anyLong(), anyString())).thenReturn(null);
        when(chatApi.listRecentProactive(eq(1L), anyInt())).thenReturn(List.of());

        ProactiveDispatcher dispatcher = new ProactiveDispatcher(
                characterApi, memoryApi, chatApi, frequencyGate, List.of(followup), new ObjectMapper(), repo);

        EventPendingEntity event = EventPendingTestFixtures.withPayload(
                1L, 99L, EventType.C_EVENT_FOLLOWUP, Instant.now(), "{\"memoryItemId\": 555}");

        dispatcher.dispatch(event);

        assertThat(event.getStatus()).isEqualTo(EventStatus.SENT);
        verify(repo).save(event);
        verify(memoryApi).markMemoryItemDone(555L);
    }

    @Test
    void deliver_should_feed_recent_proactive_into_context() {
        when(characterApi.findOrCreateRelationship(1L, 99L)).thenReturn(relAtStage(2));
        when(frequencyGate.allow(1L, 99L, EventType.A_GREETING, 2)).thenReturn(true);
        when(characterApi.getStagePromptSegment(1L, 99L)).thenReturn("当前关系阶段：暧昧。");
        when(memoryApi.getRelevantContext(eq(1L), eq(99L), anyString())).thenReturn(new MemoryContext("记忆片段"));
        when(greetingGenerator.supportsType()).thenReturn(EventType.A_GREETING);
        when(greetingGenerator.generate(any(GenerateContext.class))).thenReturn(List.of("再发一句"));
        when(chatApi.listRecentProactive(eq(1L), anyInt()))
                .thenReturn(List.of(new MessageDto(9L, 1L, "ai", "早安呀", LocalDateTime.now())));

        EventPendingEntity event = EventPendingTestFixtures.scheduled(
                1L, 99L, EventType.A_GREETING, Instant.now());

        newDispatcher().dispatch(event);

        ArgumentCaptor<GenerateContext> cap = ArgumentCaptor.forClass(GenerateContext.class);
        verify(greetingGenerator).generate(cap.capture());
        assertThat(cap.getValue().recentProactiveMessages()).containsExactly("早安呀");
    }

    @Test
    void deliver_should_still_send_when_listRecentProactive_throws() {
        when(characterApi.findOrCreateRelationship(1L, 99L)).thenReturn(relAtStage(2));
        when(frequencyGate.allow(1L, 99L, EventType.A_GREETING, 2)).thenReturn(true);
        when(characterApi.getStagePromptSegment(1L, 99L)).thenReturn("当前关系阶段：暧昧。");
        when(memoryApi.getRelevantContext(eq(1L), eq(99L), anyString())).thenReturn(new MemoryContext("记忆片段"));
        when(greetingGenerator.supportsType()).thenReturn(EventType.A_GREETING);
        when(greetingGenerator.generate(any(GenerateContext.class))).thenReturn(List.of("早安宝"));
        // 反重复查询（跨模块 DB 查询）抛异常 —— 只是锦上添花，应降级为"不加反重复段、照常发"
        when(chatApi.listRecentProactive(eq(1L), anyInt())).thenThrow(new RuntimeException("db down"));

        EventPendingEntity event = EventPendingTestFixtures.scheduled(
                1L, 99L, EventType.A_GREETING, Instant.now());
        event.setFailCount(0);

        newDispatcher().dispatch(event);

        // 降级而非打断：仍生成、仍投递、终态 SENT、不退避
        assertThat(event.getStatus()).isEqualTo(EventStatus.SENT);
        assertThat(event.getFailCount()).isEqualTo(0);
        verify(chatApi).deliverProactiveMessage(1L, 99L, List.of("早安宝"));
        verify(repo).save(event);

        // 降级证据：ctx.recentProactiveMessages() 为空 list
        ArgumentCaptor<GenerateContext> cap = ArgumentCaptor.forClass(GenerateContext.class);
        verify(greetingGenerator).generate(cap.capture());
        assertThat(cap.getValue().recentProactiveMessages()).isEmpty();
    }

    @Test
    void dispatch_failure_under_max_should_backoff_to_scheduled_and_save() {
        when(characterApi.findOrCreateRelationship(1L, 99L)).thenReturn(relAtStage(2));
        when(frequencyGate.allow(1L, 99L, EventType.B_RECALL, 2)).thenReturn(true);
        when(greetingGenerator.supportsType()).thenReturn(EventType.A_GREETING); // 只有 greeting，无 recall 生成器 → 抛 BusinessException

        EventPendingEntity event = EventPendingTestFixtures.scheduled(
                1L, 99L, EventType.B_RECALL, Instant.now());
        event.setFailCount(0);

        // 异常在 worker 内 catch 不外泄
        newDispatcher().dispatch(event);

        assertThat(event.getStatus()).isEqualTo(EventStatus.SCHEDULED);
        assertThat(event.getFailCount()).isEqualTo(1);
        assertThat(event.getLastError()).isNotBlank();
        assertThat(event.getScheduledAt()).isAfter(Instant.now());
        verify(repo).save(event);
    }

    @Test
    void dispatch_failure_over_max_should_mark_failed_and_save() {
        when(characterApi.findOrCreateRelationship(1L, 99L)).thenReturn(relAtStage(2));
        when(frequencyGate.allow(1L, 99L, EventType.B_RECALL, 2)).thenReturn(true);
        when(greetingGenerator.supportsType()).thenReturn(EventType.A_GREETING); // 无 recall 生成器 → 抛 BusinessException

        EventPendingEntity event = EventPendingTestFixtures.scheduled(
                1L, 99L, EventType.B_RECALL, Instant.now());
        event.setFailCount(3); // 已失败 3 次，再失败 → 超限

        newDispatcher().dispatch(event);

        assertThat(event.getStatus()).isEqualTo(EventStatus.FAILED);
        assertThat(event.getFailCount()).isEqualTo(4);
        verify(repo).save(event);
    }
}
