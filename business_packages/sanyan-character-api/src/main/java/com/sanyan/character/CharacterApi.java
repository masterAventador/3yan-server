package com.sanyan.character;

import com.sanyan.character.dto.AiCharacterDto;
import com.sanyan.character.dto.RelationshipDto;

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

    // ----- Plan 3 新增（关系 / Stage prompt） -----

    /** 唯一懒创建入口：返回 user × character 关系；不存在则创建一条 score=0 stage=0 的记录。 */
    RelationshipDto findOrCreateRelationship(Long userId, Long characterId);

    /** GET /api/relationships/me 编排入口：findOrCreate + 触发每日首次登录涨分 + 拼好 DTO 返回。 */
    RelationshipDto fetchMyRelationship(Long userId, Long characterId);

    /**
     * 返回拼好的 stage prompt 片段，供 chat-core 注入到 system 消息。
     * 示例返回："当前关系阶段：暧昧。称呼用户用：宝。语调：撒娇、试探、害羞。"
     * 当 character 不存在 / persona_config 缺失 / stage 无 override 时返回空字符串。
     */
    String getStagePromptSegment(Long userId, Long characterId);
}
