package com.sanyan.controller;

import com.sanyan.entity.AiCharacter;
import com.sanyan.repository.AiCharacterRepository;
import com.sanyan.service.SmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CharacterControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AiCharacterRepository characterRepository;
    @MockBean private SmsService smsService;

    private Long characterId;

    @BeforeEach
    void setUp() {
        characterRepository.deleteAll();
        AiCharacter c = new AiCharacter();
        c.setName("小晚");
        c.setAvatar("avatar_url");
        c.setSystemPrompt("你是小晚，一个温柔的AI陪伴者");
        c.setGreeting("你好，我是小晚，很高兴认识你！");
        c.setType("preset");
        AiCharacter saved = characterRepository.save(c);
        characterId = saved.getId();
    }

    @Test
    void shouldListPresetCharacters() throws Exception {
        mockMvc.perform(get("/api/characters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].name").value("小晚"));
    }

    @Test
    void shouldGetCharacterDetail() throws Exception {
        mockMvc.perform(get("/api/characters/" + characterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("小晚"));
    }

    @Test
    void shouldReturnFailForNonExistentCharacter() throws Exception {
        mockMvc.perform(get("/api/characters/99999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errMsg").value("角色不存在"));
    }
}
