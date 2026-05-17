package com.sanyan.character.event;

import com.sanyan.character.internal.plotrule.RelationshipMilestoneEntity;
import com.sanyan.character.internal.plotrule.RelationshipMilestoneRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StageTransitionStoryListenerTest {

    @Mock RelationshipMilestoneRepository milestoneRepo;
    @Mock ApplicationEventPublisher publisher;
    @InjectMocks StageTransitionStoryListener listener;

    @Test
    void should_save_milestone_and_publish_story_event_once() {
        when(milestoneRepo.existsById(any())).thenReturn(false);

        listener.onStageTransition(new StageTransitionEvent(1L, 1L, 1, 2));

        verify(milestoneRepo).save(any(RelationshipMilestoneEntity.class));
        verify(publisher).publishEvent(any(StageEntryStoryEvent.class));
    }

    @Test
    void should_skip_when_milestone_already_recorded() {
        when(milestoneRepo.existsById(any())).thenReturn(true);

        listener.onStageTransition(new StageTransitionEvent(1L, 1L, 1, 2));

        verify(milestoneRepo, never()).save(any());
        verifyNoInteractions(publisher);
    }

    @Test
    void story_message_should_match_to_stage() {
        when(milestoneRepo.existsById(any())).thenReturn(false);

        listener.onStageTransition(new StageTransitionEvent(1L, 1L, 2, 3));

        verify(publisher).publishEvent(argThat((StageEntryStoryEvent e) ->
                e.toStage() == 3
                && e.storyMessage().contains("宝贝")
                && e.userId().equals(1L)));
    }
}
