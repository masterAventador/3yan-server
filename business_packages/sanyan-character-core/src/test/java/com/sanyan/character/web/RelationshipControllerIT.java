package com.sanyan.character.web;

import com.sanyan.character.CharacterApi;
import com.sanyan.character.dto.RelationshipDto;
import com.sanyan.common.auth.JwtUtil;
import com.sanyan.common.auth.LoginUserArgumentResolver;
import com.sanyan.common.auth.WebMvcConfig;
import com.sanyan.common.test.TestApplication;
import com.sanyan.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RelationshipController WebMvc 切片测试。
 *
 * <p>LoginUserArgumentResolver 从 Authorization header 取 token，再通过 JwtUtil 解析 userId。
 * 测试中 Mock JwtUtil，让 "test-token" 固定返回 userId=42，避免依赖真实 JWT 签名逻辑。
 */
@WebMvcTest(controllers = RelationshipController.class)
@ContextConfiguration(classes = TestApplication.class)
@Import({RelationshipController.class, WebMvcConfig.class, LoginUserArgumentResolver.class, GlobalExceptionHandler.class})
class RelationshipControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CharacterApi characterApi;

    @MockBean
    private JwtUtil jwtUtil;

    @Test
    void get_me_should_return_relationship_dto() throws Exception {
        // Arrange
        long userId = 42L;
        long characterId = 1L;
        when(jwtUtil.parseUserId("test-token"))
                .thenReturn(userId);
        when(characterApi.fetchMyRelationship(userId, characterId))
                .thenReturn(new RelationshipDto(userId, characterId, 150, 1, "朋友", 300, 0.25));

        // Act & Assert
        mockMvc.perform(get("/api/relationships/me")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(userId))
                .andExpect(jsonPath("$.data.characterId").value(characterId))
                .andExpect(jsonPath("$.data.intimacyScore").value(150))
                .andExpect(jsonPath("$.data.currentStage").value(1))
                .andExpect(jsonPath("$.data.currentStageName").value("朋友"))
                .andExpect(jsonPath("$.data.nextStageThreshold").value(300));
    }
}
