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

    private static final long ROUND_TIMEOUT_MS = 600_000L; // 10 minutes

    private final StringRedisTemplate redisTemplate;
    private final MemoryService memoryService;

    /**
     * Every minute: scan for conversation rounds that have been idle for 10+ minutes,
     * trigger async memory update, then clean up the Redis keys.
     */
    @Scheduled(fixedRate = 60000)
    public void checkRoundEnd() {
        Set<String> tsKeys = redisTemplate.keys("conv:round:*:ts");
        if (tsKeys == null || tsKeys.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (String tsKey : tsKeys) {
            try {
                String tsStr = redisTemplate.opsForValue().get(tsKey);
                if (tsStr == null) continue;

                long lastTs = Long.parseLong(tsStr);
                if (now - lastTs < ROUND_TIMEOUT_MS) {
                    continue;
                }

                // Extract conversationId from key pattern "conv:round:{id}:ts"
                String roundKey = tsKey.substring(0, tsKey.length() - ":ts".length());
                String convIdStr = roundKey.substring("conv:round:".length());
                Long conversationId = Long.parseLong(convIdStr);

                // Get all message IDs from the list key
                List<String> idStrs = redisTemplate.opsForList().range(roundKey, 0, -1);
                List<Long> messageIds = new ArrayList<>();
                if (idStrs != null) {
                    for (String idStr : idStrs) {
                        try {
                            messageIds.add(Long.parseLong(idStr));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }

                // Delete Redis keys before triggering async task
                redisTemplate.delete(roundKey);
                redisTemplate.delete(tsKey);

                log.info("检测到会话轮次结束，触发记忆更新，会话ID={}，消息数={}", conversationId, messageIds.size());
                memoryService.updateMemory(conversationId, messageIds);

            } catch (Exception e) {
                log.error("处理记忆轮次检测失败，key={}", tsKey, e);
            }
        }
    }
}
