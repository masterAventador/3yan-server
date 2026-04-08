package com.sanyan.controller;

import com.sanyan.dto.ApiResponse;
import com.sanyan.dto.data.CharacterData;
import com.sanyan.entity.AiCharacter;
import com.sanyan.repository.AiCharacterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/characters")
@RequiredArgsConstructor
public class CharacterController {

    private final AiCharacterRepository characterRepository;

    @GetMapping
    public ApiResponse<List<CharacterData>> list() {
        List<CharacterData> list = characterRepository.findByType("preset").stream()
                .map(this::toData).toList();
        return ApiResponse.ok(list);
    }

    @GetMapping("/{id}")
    public ApiResponse<CharacterData> detail(@PathVariable Long id) {
        return characterRepository.findById(id)
                .map(c -> ApiResponse.ok(toData(c)))
                .orElse(ApiResponse.fail("角色不存在"));
    }

    private CharacterData toData(AiCharacter c) {
        CharacterData d = new CharacterData();
        d.setId(c.getId());
        d.setName(c.getName());
        d.setAvatar(c.getAvatar());
        d.setGreeting(c.getGreeting());
        return d;
    }
}
