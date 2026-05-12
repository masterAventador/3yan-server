package com.sanyan.user.internal;

/**
 * UserEntity Object Mother (java-backend-business-layer.md §5.2)。
 * 测试中需要 UserEntity 时一律通过这里构造，不允许裸 new。
 */
public final class UserTestFixtures {

    private UserTestFixtures() {}

    public static UserEntity validUser() {
        UserEntity u = new UserEntity();
        u.setPhone("13800138000");
        u.setNickname("测试用户");
        u.setPassword("$2a$10$encrypted");
        u.setAvatar(null);
        return u;
    }

    public static UserEntity userWithPhone(String phone) {
        UserEntity u = validUser();
        u.setPhone(phone);
        return u;
    }

    public static UserEntity userWithId(Long id) {
        UserEntity u = validUser();
        u.setId(id);
        return u;
    }
}
