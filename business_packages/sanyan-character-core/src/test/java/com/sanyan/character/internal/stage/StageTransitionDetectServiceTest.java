package com.sanyan.character.internal.stage;

import com.sanyan.character.event.StageTransitionEvent;
import com.sanyan.character.internal.RelationshipEntity;
import com.sanyan.character.internal.RelationshipRepository;
import com.sanyan.character.internal.fixtures.RelationshipTestFixtures;
import com.sanyan.character.internal.intimacy.IntimacyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StageTransitionDetectServiceTest {

    @Mock ApplicationEventPublisher publisher;
    @Mock RelationshipRepository repo;
    StageDefinition stageDef;
    StageTransitionDetectService service;

    @BeforeEach
    void setup() {
        IntimacyProperties props = new IntimacyProperties();
        props.getStages().setStrangerEnd(100);
        props.getStages().setFriendEnd(300);
        props.getStages().setAmbiguousEnd(600);
        props.getStages().setLoverEnd(1000);
        stageDef = new StageDefinition(props);
        service = new StageTransitionDetectService(stageDef, repo, publisher);
    }

    @Test
    void should_publish_event_when_crossing_boundary() {
        RelationshipEntity rel = RelationshipTestFixtures.relationshipAtStage(1L, 1L, 0, 95);
        service.maybeTransition(rel, 105);

        assertThat(rel.getCurrentStage()).isEqualTo((short) 1);
        verify(repo).save(rel);
        verify(publisher).publishEvent(any(StageTransitionEvent.class));
    }

    @Test
    void should_not_publish_event_when_same_stage() {
        RelationshipEntity rel = RelationshipTestFixtures.relationshipAtStage(1L, 1L, 1, 200);
        service.maybeTransition(rel, 250);

        assertThat(rel.getCurrentStage()).isEqualTo((short) 1);
        verifyNoInteractions(publisher);
        verify(repo, never()).save(any());
    }

    @Test
    void should_publish_event_with_correct_from_to() {
        RelationshipEntity rel = RelationshipTestFixtures.relationshipAtStage(1L, 1L, 2, 599);
        service.maybeTransition(rel, 600);

        assertThat(rel.getCurrentStage()).isEqualTo((short) 3);
        verify(publisher).publishEvent(ArgumentMatchers.<StageTransitionEvent>argThat(e ->
                e.fromStage() == 2 && e.toStage() == 3
                && e.userId().equals(1L) && e.characterId().equals(1L)));
    }
}
