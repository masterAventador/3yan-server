package com.sanyan.user.api;

import com.sanyan.user.UserApi;
import com.sanyan.user.dto.UserDto;
import com.sanyan.user.internal.UserEntity;
import com.sanyan.user.internal.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * UserApi 实现（S3 Phase 1）。
 * 委托 UserRepository 完成查询；当前需求只暴露 findById / existsByPhone 两个查询。
 *
 * <p>映射注意：UserEntity 字段名是 {@code avatar}（不是 avatarUrl），
 * UserDto 对应字段名是 {@code avatarUrl}——这里通过位置传参把 entity.avatar 喂到 dto.avatarUrl 位上。
 * 名字不同是历史遗留，后续若有需要再统一。
 */
@Service
@RequiredArgsConstructor
public class UserApiImpl implements UserApi {

    private final UserRepository repository;

    @Override
    public UserDto findById(Long userId) {
        return repository.findById(userId)
                .map(this::toDto)
                .orElse(null);
    }

    @Override
    public boolean existsByPhone(String phone) {
        return repository.existsByPhone(phone);
    }

    private UserDto toDto(UserEntity e) {
        return new UserDto(e.getId(), e.getPhone(), e.getNickname(), e.getAvatar());
    }
}
