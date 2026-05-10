package com.sanyan.scheduler;

import com.sanyan.service.MemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryScheduler {

    private static final long ROUND_TIMEOUT_MS = 600_000L; // 10 分钟

    private final StringRedisTemplate redisTemplate;
    private final MemoryService memoryService;

    /**
     * 每分钟扫一次：找空闲超 10 分钟的用户对话轮次，触发记忆更新，再清 Redis。
     */
    @Scheduled(fixedRate = 60_000)
    public void checkRoundEnd() {
        Set<String> tsKeys = redisTemplate.keys("user:round:*:ts");
        if (tsKeys == null || tsKeys.isEmpty()) return;

        long now = System.currentTimeMillis();
        for (String tsKey : tsKeys) {
            try {
                String tsStr = redisTemplate.opsForValue().get(tsKey);
                if (tsStr == null) continue;

                long lastTs = Long.parseLong(tsStr);
                if (now - lastTs < ROUND_TIMEOUT_MS) continue;

                // tsKey 形如 "user:round:{id}:ts"
                String roundKey = tsKey.substring(0, tsKey.length() - ":ts".length());
                String userIdStr = roundKey.substring("user:round:".length());
                Long userId = Long.parseLong(userIdStr);

                List<String> idStrs = redisTemplate.opsForList().range(roundKey, 0, -1);
                List<Long> messageIds = new ArrayList<>();
                if (idStrs != null) {
                    for (String idStr : idStrs) {
                        try {
                            messageIds.add(Long.parseLong(idStr));
                        } catch (NumberFormatException ignored) {}
                    }
                }

                redisTemplate.delete(roundKey);
                redisTemplate.delete(tsKey);

                log.info("检测到对话轮次结束，触发记忆更新，userId={}，消息数={}", userId, messageIds.size());
                memoryService.updateMemory(userId, messageIds);

            } catch (Exception e) {
                log.error("处理记忆轮次检测失败，key={}", tsKey, e);
            }
        }
    }
}
