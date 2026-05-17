package com.sanyan.user;

import com.sanyan.user.dto.UserDto;

/**
 * User 域对内 API 契约。其他业务 -core 只能依赖本接口，不能引 user-core/internal/。
 *
 * <p>本接口故意保持窄（findById / existsByPhone）—— 当前业务里其他域只需要这两个查询。
 * 后续如果有跨模块更新需求（如 chat 模块要改 nickname），先加到本接口再实现，
 * 不允许其他模块绕过 UserApi 直接动 UserEntity。
 */
public interface UserApi {
    /** 按 userId 查用户摘要；不存在返回 null。 */
    UserDto findById(Long userId);

    /** 检查手机号是否已注册。用于注册前的校验。 */
    boolean existsByPhone(String phone);
}
