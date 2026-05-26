package com.sanyan.character.api;

import com.sanyan.character.internal.RelationshipRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterApiImplListActiveUsersTest {

    @Mock RelationshipRepository relationshipRepository;
    @InjectMocks CharacterApiImpl api;

    @Test
    void listActiveRelationshipUserIds_should_delegate_to_repository() {
        when(relationshipRepository.findUserIdsByCharacterId(1L))
                .thenReturn(List.of(10L, 20L, 30L));

        List<Long> ids = api.listActiveRelationshipUserIds(1L);

        assertThat(ids).containsExactly(10L, 20L, 30L);
    }
}
