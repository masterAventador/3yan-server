package com.sanyan.character.event;

import com.sanyan.character.internal.RelationshipEntity;
import com.sanyan.character.internal.RelationshipRepository;
import com.sanyan.character.internal.fixtures.RelationshipTestFixtures;
import com.sanyan.character.internal.intimacy.IntimacyEvent;
import com.sanyan.character.internal.intimacy.IntimacyProperties;
import com.sanyan.character.internal.intimacy.IntimacyRecordService;
import com.sanyan.character.internal.intimacy.ai.ConversationQualityEvaluator;
import com.sanyan.character.internal.intimacy.ai.QualityScoreResponse;
import com.sanyan.character.internal.plotrule.PlotMilestoneEngine;
import com.sanyan.character.internal.plotrule.RelationshipMilestoneEntity;
import com.sanyan.character.internal.plotrule.RelationshipMilestoneId;
import com.sanyan.character.internal.plotrule.RelationshipMilestoneRepository;
import com.sanyan.chat.ChatApi;
import com.sanyan.chat.dto.MessageDto;
import com.sanyan.chat.event.MessagePersistedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessagePersistedListenerTest {

    @Mock IntimacyRecordService recordService;
    @Mock PlotMilestoneEngine plotEngine;
    @Mock ConversationQualityEvaluator evaluator;
    @Mock ChatApi chatApi;
    @Mock RelationshipRepository relRepo;
    @Mock RelationshipMilestoneRepository milestoneRepo;
    IntimacyProperties props;
    MessagePersistedListener listener;

    @BeforeEach
    void setup() {
        props = new IntimacyProperties();  // ai.triggerEveryNMessages 默认 10
        listener = new MessagePersistedListener(
                recordService, plotEngine, evaluator,
                chatApi, relRepo, milestoneRepo, props);
    }

    @Test
    void should_always_record_message_sent_on_user_message() {
        when(relRepo.findByUserIdAndCharacterId(1L, 1L))
                .thenReturn(Optional.of(RelationshipTestFixtures.validRelationship(1L, 1L)));
        when(chatApi.listRecentByUser(1L, 32)).thenReturn(List.of());
        when(milestoneRepo.findAllByUserIdAndCharacterId(1L, 1L)).thenReturn(List.of());
        when(plotEngine.evaluate(any())).thenReturn(List.of());

        listener.onMessagePersisted(new MessagePersistedEvent(99L, 1L, 1L, "user"));

        verify(recordService).recordEvent(argThat(e ->
                e.type() == IntimacyEvent.Type.MESSAGE_SENT));
    }

    @Test
    void should_skip_on_ai_sender() {
        listener.onMessagePersisted(new MessagePersistedEvent(99L, 1L, 1L, "ai"));
        verifyNoInteractions(recordService);
    }

    @Test
    void should_dispatch_plot_events() {
        when(relRepo.findByUserIdAndCharacterId(1L, 1L))
                .thenReturn(Optional.of(RelationshipTestFixtures.validRelationship(1L, 1L)));
        when(chatApi.listRecentByUser(1L, 32)).thenReturn(List.of());
        when(milestoneRepo.findAllByUserIdAndCharacterId(1L, 1L)).thenReturn(List.of());
        when(plotEngine.evaluate(any()))
                .thenReturn(List.of(IntimacyEvent.plot(1L, 1L, "deep_night_chat", 50)));

        listener.onMessagePersisted(new MessagePersistedEvent(99L, 1L, 1L, "user"));

        verify(recordService).recordEvent(argThat(e ->
                e.type() == IntimacyEvent.Type.PLOT_MILESTONE
                && "deep_night_chat".equals(e.payloadStr())));
    }

    /**
     * 回归：PLOT 触发后必须 save 到 relationship_milestones 表（去重表）。
     * 否则下次新消息会再次触发同一规则（plotEngine 看到 triggeredRuleIds 为空），
     * 且 dogfood 断言 milestone 表存在记录会失败。
     */
    @Test
    void should_save_milestone_when_plot_triggers() {
        when(relRepo.findByUserIdAndCharacterId(1L, 1L))
                .thenReturn(Optional.of(RelationshipTestFixtures.validRelationship(1L, 1L)));
        when(chatApi.listRecentByUser(1L, 32)).thenReturn(List.of());
        when(milestoneRepo.findAllByUserIdAndCharacterId(1L, 1L)).thenReturn(List.of());
        when(milestoneRepo.existsById(any(RelationshipMilestoneId.class))).thenReturn(false);
        when(plotEngine.evaluate(any()))
                .thenReturn(List.of(IntimacyEvent.plot(1L, 1L, "deep_night_chat", 50)));

        listener.onMessagePersisted(new MessagePersistedEvent(99L, 1L, 1L, "user"));

        verify(milestoneRepo).save(argThat((RelationshipMilestoneEntity m) ->
                m.getUserId().equals(1L)
                && m.getCharacterId().equals(1L)
                && "deep_night_chat".equals(m.getRuleId())));
    }

    /**
     * 回归：相同 ruleId 已 save 过时不应重复 save（existsById=true 时跳过）。
     */
    @Test
    void should_skip_save_milestone_when_already_triggered() {
        when(relRepo.findByUserIdAndCharacterId(1L, 1L))
                .thenReturn(Optional.of(RelationshipTestFixtures.validRelationship(1L, 1L)));
        when(chatApi.listRecentByUser(1L, 32)).thenReturn(List.of());
        when(milestoneRepo.findAllByUserIdAndCharacterId(1L, 1L)).thenReturn(List.of());
        when(milestoneRepo.existsById(any(RelationshipMilestoneId.class))).thenReturn(true);
        when(plotEngine.evaluate(any()))
                .thenReturn(List.of(IntimacyEvent.plot(1L, 1L, "deep_night_chat", 50)));

        listener.onMessagePersisted(new MessagePersistedEvent(99L, 1L, 1L, "user"));

        verify(milestoneRepo, never()).save(any(RelationshipMilestoneEntity.class));
    }

    @Test
    void should_trigger_ai_eval_every_n_messages() {
        when(relRepo.findByUserIdAndCharacterId(1L, 1L))
                .thenReturn(Optional.of(RelationshipTestFixtures.validRelationship(1L, 1L)));
        var msgs = IntStream.range(0, 10).mapToObj(i -> userMsg("hi")).toList();
        when(chatApi.listRecentByUser(1L, 32)).thenReturn(msgs);
        when(milestoneRepo.findAllByUserIdAndCharacterId(1L, 1L)).thenReturn(List.of());
        when(plotEngine.evaluate(any())).thenReturn(List.of());
        when(evaluator.score(any())).thenReturn(new QualityScoreResponse(15, "深度"));

        listener.onMessagePersisted(new MessagePersistedEvent(99L, 1L, 1L, "user"));

        verify(recordService).recordEvent(argThat(e ->
                e.type() == IntimacyEvent.Type.AI_QUALITY_BONUS
                && e.payloadInt() == 15));
    }

    @Test
    void should_lazy_create_relationship_if_absent() {
        when(relRepo.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.empty());
        when(chatApi.listRecentByUser(1L, 32)).thenReturn(List.of());
        when(milestoneRepo.findAllByUserIdAndCharacterId(1L, 1L)).thenReturn(List.of());
        when(plotEngine.evaluate(any())).thenReturn(List.of());

        listener.onMessagePersisted(new MessagePersistedEvent(99L, 1L, 1L, "user"));

        verify(relRepo).save(any(RelationshipEntity.class));
    }

    private MessageDto userMsg(String content) {
        return new MessageDto(1L, 1L, "user", content, LocalDateTime.now());
    }
}
