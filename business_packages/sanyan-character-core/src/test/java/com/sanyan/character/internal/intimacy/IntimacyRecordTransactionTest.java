package com.sanyan.character.internal.intimacy;

import com.sanyan.character.event.IntimacyChangedEvent;
import com.sanyan.character.internal.CharacterErrCode;
import com.sanyan.character.internal.RelationshipEntity;
import com.sanyan.character.internal.RelationshipRepository;
import com.sanyan.character.internal.fixtures.RelationshipTestFixtures;
import com.sanyan.character.internal.stage.StageDefinition;
import com.sanyan.character.internal.stage.StageTransitionDetectService;
import com.sanyan.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * 测试 {@link IntimacyRecordTransaction#doRecord} 的核心逻辑：
 * 累加分值、阶段切换检测、日志写入、事件发布。
 */
@ExtendWith(MockitoExtension.class)
class IntimacyRecordTransactionTest {

    @Mock RelationshipRepository relRepo;
    @Mock IntimacyLogRepository logRepo;
    @Mock IntimacyCalculator calculator;
    @Mock DailyBehaviorCounter dailyCounter;
    @Mock StageTransitionDetectService stageTransition;
    @Mock ApplicationEventPublisher publisher;
    @Mock StageDefinition stageDef;
    @InjectMocks IntimacyRecordTransaction tx;

    @Test
    void should_accumulate_and_publish_intimacy_changed_event() {
        RelationshipEntity rel = RelationshipTestFixtures.relationshipWithScore(1L, 1L, 50);
        when(relRepo.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(rel));
        when(calculator.compute(any())).thenReturn(5);
        when(stageDef.stageFor(55)).thenReturn(0);

        tx.doRecord(IntimacyEvent.messageSent(1L, 1L));

        assertThat(rel.getIntimacyScore()).isEqualTo(55);
        verify(logRepo).save(any(IntimacyLogEntity.class));
        verify(publisher).publishEvent(any(IntimacyChangedEvent.class));
        verify(dailyCounter).incr(1L, 5);  // MESSAGE_SENT + delta>0 → 累计
    }

    @Test
    void should_call_stage_transition_when_score_changes() {
        RelationshipEntity rel = RelationshipTestFixtures.relationshipWithScore(1L, 1L, 95);
        when(relRepo.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(rel));
        when(calculator.compute(any())).thenReturn(10);

        tx.doRecord(IntimacyEvent.messageSent(1L, 1L));

        verify(stageTransition).maybeTransition(rel, 105);
    }

    @Test
    void should_still_write_log_when_delta_is_zero_capped() {
        RelationshipEntity rel = RelationshipTestFixtures.relationshipWithScore(1L, 1L, 50);
        when(relRepo.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(rel));
        when(calculator.compute(any())).thenReturn(0);

        tx.doRecord(IntimacyEvent.messageSent(1L, 1L));

        ArgumentCaptor<IntimacyLogEntity> cap = ArgumentCaptor.forClass(IntimacyLogEntity.class);
        verify(logRepo).save(cap.capture());
        assertThat(cap.getValue().getReason()).isEqualTo("CAPPED");
        verify(dailyCounter, never()).incr(any(), anyInt());
    }

    @Test
    void should_throw_relationship_not_found_when_missing() {
        when(relRepo.findByUserIdAndCharacterId(999L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tx.doRecord(IntimacyEvent.messageSent(999L, 1L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errCode", CharacterErrCode.RELATIONSHIP_NOT_FOUND);
    }

    @Test
    void plot_milestone_reason_should_include_rule_id() {
        RelationshipEntity rel = RelationshipTestFixtures.relationshipWithScore(1L, 1L, 50);
        when(relRepo.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(rel));
        when(calculator.compute(any())).thenReturn(50);

        tx.doRecord(IntimacyEvent.plot(1L, 1L, "deep_night_chat", 50));

        ArgumentCaptor<IntimacyLogEntity> cap = ArgumentCaptor.forClass(IntimacyLogEntity.class);
        verify(logRepo).save(cap.capture());
        assertThat(cap.getValue().getReason()).isEqualTo("PLOT:deep_night_chat");
    }
}
