package com.sanyan.character.internal;

import com.sanyan.character.internal.fixtures.RelationshipTestFixtures;
import com.sanyan.character.internal.intimacy.ConsecutiveLoginService;
import com.sanyan.character.internal.intimacy.IntimacyEvent;
import com.sanyan.character.internal.intimacy.IntimacyRecordService;
import com.sanyan.character.internal.stage.StageDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RelationshipFetchServiceTest {
    @Mock RelationshipFindOrCreateService findOrCreateService;
    @Mock ConsecutiveLoginService loginService;
    @Mock IntimacyRecordService intimacyRecordService;
    @Mock StageDefinition stageDef;
    @InjectMocks RelationshipFetchService service;

    @Test
    void should_record_daily_login_only_on_first_today() {
        var rel = RelationshipTestFixtures.relationshipWithScore(1L, 1L, 50);
        when(findOrCreateService.findOrCreate(1L, 1L)).thenReturn(rel);
        when(loginService.recordLogin(eq(1L), any(LocalDate.class)))
                .thenReturn(new ConsecutiveLoginService.LoginResult(true, 3));
        when(stageDef.nameOf(0)).thenReturn("陌生人");
        when(stageDef.nextThresholdOf(0)).thenReturn(100);

        var dto = service.fetchMyRelationship(1L, 1L);

        verify(intimacyRecordService).recordEvent(argThat(e ->
                e.type() == IntimacyEvent.Type.DAILY_LOGIN
                && e.payloadInt() == 3));
        assertThat(dto.currentStageName()).isEqualTo("陌生人");
    }

    @Test
    void should_skip_recording_when_not_first_today() {
        var rel = RelationshipTestFixtures.relationshipWithScore(1L, 1L, 50);
        when(findOrCreateService.findOrCreate(1L, 1L)).thenReturn(rel);
        when(loginService.recordLogin(eq(1L), any(LocalDate.class)))
                .thenReturn(new ConsecutiveLoginService.LoginResult(false, 3));
        when(stageDef.nameOf(0)).thenReturn("陌生人");
        when(stageDef.nextThresholdOf(0)).thenReturn(100);

        service.fetchMyRelationship(1L, 1L);
        verifyNoInteractions(intimacyRecordService);
    }

    @Test
    void should_compute_percent_for_mid_stage() {
        var rel = RelationshipTestFixtures.relationshipAtStage(1L, 1L, 1, 200);
        when(findOrCreateService.findOrCreate(1L, 1L)).thenReturn(rel);
        when(loginService.recordLogin(eq(1L), any(LocalDate.class)))
                .thenReturn(new ConsecutiveLoginService.LoginResult(false, 0));
        when(stageDef.nameOf(1)).thenReturn("朋友");
        when(stageDef.nextThresholdOf(0)).thenReturn(100);
        when(stageDef.nextThresholdOf(1)).thenReturn(300);

        var dto = service.fetchMyRelationship(1L, 1L);

        // (200 - 100) / (300 - 100) = 0.5
        assertThat(dto.percentToNextStage()).isEqualTo(0.5);
        assertThat(dto.nextStageThreshold()).isEqualTo(300);
    }

    @Test
    void should_return_full_percent_at_top_stage() {
        var rel = RelationshipTestFixtures.relationshipAtStage(1L, 1L, 4, 5000);
        when(findOrCreateService.findOrCreate(1L, 1L)).thenReturn(rel);
        when(loginService.recordLogin(eq(1L), any(LocalDate.class)))
                .thenReturn(new ConsecutiveLoginService.LoginResult(false, 0));
        when(stageDef.nameOf(4)).thenReturn("老夫老妻");
        when(stageDef.nextThresholdOf(3)).thenReturn(1000);
        when(stageDef.nextThresholdOf(4)).thenReturn(Integer.MAX_VALUE);

        var dto = service.fetchMyRelationship(1L, 1L);

        assertThat(dto.percentToNextStage()).isEqualTo(1.0);
        assertThat(dto.nextStageThreshold()).isEqualTo(Integer.MAX_VALUE);
    }
}
