package com.sanyan.user.dto;

/**
 * User 跨模块 DTO。字段是其他业务实际需要的最小集合。
 *
 * <p>不要直接暴露 UserEntity 的 passwordHash / smsCode 等内部字段。
 * 如果某个跨模块场景需要新字段，先在本 record 加，再让 UserApiImpl 填充。
 */
public record UserDto(
        Long id,
        String phone,
        String nickname,
        String avatarUrl) {}
