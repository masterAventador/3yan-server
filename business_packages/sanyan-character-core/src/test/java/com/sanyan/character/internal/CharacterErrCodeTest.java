package com.sanyan.character.internal;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CharacterErrCodeTest {
    @Test
    void should_have_relationship_not_found_3002() {
        assertThat(CharacterErrCode.RELATIONSHIP_NOT_FOUND.getCode()).isEqualTo(3002);
        assertThat(CharacterErrCode.RELATIONSHIP_NOT_FOUND.getDefaultMessage()).isEqualTo("关系不存在");
    }

    @Test
    void should_have_intimacy_concurrent_update_3003() {
        assertThat(CharacterErrCode.INTIMACY_CONCURRENT_UPDATE.getCode()).isEqualTo(3003);
        assertThat(CharacterErrCode.INTIMACY_CONCURRENT_UPDATE.getDefaultMessage()).isEqualTo("亲密度并发更新失败");
    }
}
