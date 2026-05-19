package com.sanyan.character.internal;

import com.sanyan.character.internal.fixtures.RelationshipTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RelationshipFindOrCreateServiceTest {

    @Mock
    RelationshipRepository repo;

    @InjectMocks
    RelationshipFindOrCreateService service;

    @Test
    void should_return_existing_relationship() {
        var rel = RelationshipTestFixtures.relationshipWithScore(1L, 1L, 100);
        when(repo.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.of(rel));

        assertThat(service.findOrCreate(1L, 1L)).isSameAs(rel);
        verify(repo, never()).save(any());
    }

    @Test
    void should_create_new_relationship_when_absent() {
        when(repo.findByUserIdAndCharacterId(1L, 1L)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var rel = service.findOrCreate(1L, 1L);

        assertThat(rel.getUserId()).isEqualTo(1L);
        assertThat(rel.getCharacterId()).isEqualTo(1L);
        assertThat(rel.getIntimacyScore()).isZero();
        assertThat(rel.getCurrentStage()).isZero();
        verify(repo).save(any(RelationshipEntity.class));
    }
}
