package com.sanyan.character.dto;

/**
 * AiCharacter 跨模块 DTO。字段为当前业务实际需要的最小集合。
 *
 * <p>当前 AiCharacterEntity 字段：{@code id / name / avatar / createdAt}。
 * 其中 {@code createdAt} 属于内部审计字段，跨模块无消费方，故不暴露。
 *
 * <p>chat / llm 当前跨模块读取的字段仅 {@code id}、{@code name}（参见 MessageService、AiService）；
 * {@code avatarUrl} 作为对外可展示字段一并暴露，便于后续 chat/notification 直接拿到，
 * 避免再追加一次 DTO 字段扩张 + 回头改 ApiImpl 的成本。
 *
 * <p>注：DTO 字段名 {@code avatarUrl} 跟 {@link com.sanyan.user.dto.UserDto#avatarUrl()} 保持一致。
 * Entity 列名是 {@code avatar}，CharacterApiImpl 做位置映射即可，不强制 Entity 列名跟 DTO 一致
 * （DTO 名是契约层命名，Entity 列名是存储层命名，解耦正常）。
 *
 * <p>未来如出现 {@code basePrompt} / {@code personaConfig} 等字段，由 character-core 加列后
 * 在本 record 增加对应字段，调用方按需消费。
 */
public record AiCharacterDto(
        Long id,
        String name,
        String avatarUrl) {}
