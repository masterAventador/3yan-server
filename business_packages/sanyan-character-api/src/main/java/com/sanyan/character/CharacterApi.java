package com.sanyan.character;

import com.sanyan.character.dto.AiCharacterDto;

/**
 * Character 域对内 API 契约。
 *
 * <p>chat / llm / 其他业务调用方需要 character 数据时走这里，不允许直接 import character-core/internal。
 *
 * <p>两个查询方法的语义对比：
 * <ul>
 *   <li>{@link #findById(Long)}：不存在返回 null（按"可空查询"语义）</li>
 *   <li>{@link #getById(Long)}：不存在抛 {@code BusinessException}（按"必须存在"语义，
 *       业务层不需要再判 null + 自己抛错）</li>
 * </ul>
 */
public interface CharacterApi {
    /** 按 id 查角色；不存在返回 null。 */
    AiCharacterDto findById(Long characterId);

    /**
     * 按 id 查角色；不存在抛 {@code BusinessException(CHARACTER_NOT_FOUND)}。
     * 业务调用方期望必定能拿到的场景用这个，避免每个调用点重复写 if-null 判断。
     */
    AiCharacterDto getById(Long characterId);
}
