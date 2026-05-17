package com.sanyan.character.internal.intimacy;

import org.springframework.stereotype.Component;

/**
 * Redis 每日行为封顶计数器。
 * Plan 3 D3 task 实现 Redis 逻辑；本骨架让 D2 IntimacyCalculator 能编译。
 */
@Component
public class DailyBehaviorCounter {

    /** 返回 userId 今日累计消耗的 delta；D3 task 实现 Redis GET。 */
    public int consumedToday(Long userId) {
        return 0;
    }

    /** 累计今日 delta；D3 task 实现 Redis INCR + 36h TTL。 */
    public void incr(Long userId, int delta) {
        // D3 task 实现
    }
}
