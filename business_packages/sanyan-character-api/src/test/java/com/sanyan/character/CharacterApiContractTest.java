package com.sanyan.character;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CharacterApiContractTest {
    @Test
    void should_have_findOrCreateRelationship_method() throws NoSuchMethodException {
        var m = CharacterApi.class.getMethod("findOrCreateRelationship", Long.class, Long.class);
        assertThat(m).isNotNull();
    }

    @Test
    void should_have_fetchMyRelationship_method() throws NoSuchMethodException {
        var m = CharacterApi.class.getMethod("fetchMyRelationship", Long.class, Long.class);
        assertThat(m).isNotNull();
    }

    @Test
    void should_have_getStagePromptSegment_method() throws NoSuchMethodException {
        var m = CharacterApi.class.getMethod("getStagePromptSegment", Long.class, Long.class);
        assertThat(m).isNotNull();
    }
}
