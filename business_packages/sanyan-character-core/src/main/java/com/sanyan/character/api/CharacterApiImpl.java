package com.sanyan.character.api;

import com.sanyan.character.CharacterApi;
import com.sanyan.character.dto.AiCharacterDto;
import com.sanyan.character.internal.AiCharacterEntity;
import com.sanyan.character.internal.AiCharacterRepository;
import com.sanyan.character.internal.CharacterErrCode;
import com.sanyan.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CharacterApiImpl implements CharacterApi {

    private final AiCharacterRepository repository;

    @Override
    public AiCharacterDto findById(Long characterId) {
        return repository.findById(characterId)
                .map(CharacterApiImpl::toDto)
                .orElse(null);
    }

    @Override
    public AiCharacterDto getById(Long characterId) {
        return repository.findById(characterId)
                .map(CharacterApiImpl::toDto)
                .orElseThrow(() -> new BusinessException(CharacterErrCode.CHARACTER_NOT_FOUND));
    }

    // 注：AiCharacterEntity 的字段是 avatar；DTO 上对应位置是 avatarUrl（命名解耦：
    // 存储层 column 命名 vs 跨模块契约命名独立）
    private static AiCharacterDto toDto(AiCharacterEntity e) {
        return new AiCharacterDto(e.getId(), e.getName(), e.getAvatar());
    }
}
