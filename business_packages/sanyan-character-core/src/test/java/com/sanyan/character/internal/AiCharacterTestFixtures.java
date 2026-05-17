package com.sanyan.character.internal;

/**
 * AiCharacterEntity Object Mother (java-backend-business-layer.md §5.2)。
 * 测试中需要 AiCharacterEntity 时一律通过这里构造，不允许裸 new。
 */
public final class AiCharacterTestFixtures {

    private AiCharacterTestFixtures() {}

    public static AiCharacterEntity xiaowan() {
        AiCharacterEntity c = new AiCharacterEntity();
        c.setId(1L);
        c.setName("小婉");
        c.setAvatar(null);
        return c;
    }

    public static AiCharacterEntity characterWithName(String name) {
        AiCharacterEntity c = xiaowan();
        c.setName(name);
        return c;
    }
}
