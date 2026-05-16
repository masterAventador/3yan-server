package com.sanyan.memory.internal.profile;

import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.chat.internal.SenderType;
import com.sanyan.common.error.BusinessException;
import com.sanyan.llm.internal.LLMProviderRouter;
import com.sanyan.llm.internal.LLMTaskType;
import com.sanyan.memory.internal.MemoryErrCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plan 2.5：自然语言画像「刷新」服务。
 *
 * <p>读出当前 {@link MemoryProfileEntity}.summaryText（不存在则空）→ 拼 LLM prompt 让 LLM
 * 基于现有画像 + 最近对话产出更新后的画像段落 → 写回（依赖 Entity {@code @Version} 字段做乐观锁）。
 *
 * <p><b>为什么用 LLM 直接维护自然语言画像（替代 Plan 2 的 slot schema）：</b>
 * 下游消费者就是 LLM（拼进 system prompt），原来"抽 slot → merge → format 回 prose"
 * 是多余的两次转换。现在让 LLM 一步直出 prose，少一次 LLM 调用 + 代码瘦身。
 *
 * <p><b>LLM 任务类型</b>：钉死 {@link LLMTaskType#BACKGROUND}——画像刷新是后台任务，
 * 用户看不到原始输出，路由到 DeepSeek V4-Flash（低成本 + 大吞吐）。
 *
 * <p><b>乐观锁处理：</b>{@link OptimisticLockingFailureException} 时重试 <b>1 次</b>
 * （重新读 → 重新刷新 → 重新保存）。再次冲突抛 {@link BusinessException}
 * ({@link MemoryErrCode#PROFILE_REFRESH_CONFLICT})，由调用方决定重新入队或丢弃。
 *
 * <p><b>容错（LLM 链路）：</b>LLM 调用失败 / 返回 blank 时 service 返回 {@code null}
 * + log.warn（不抛业务异常），调用方简单跳过即可——避免后台任务异常影响主对话。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryProfileRefreshService {

    /** 最大乐观锁重试次数（重试 1 次后失败抛异常）。 */
    private static final int MAX_OPTIMISTIC_LOCK_RETRIES = 1;

    /**
     * System prompt 模板。
     *
     * <p>关键字段（由 {@code MemoryProfileRefreshServiceTest} 守护）：
     * <ul>
     *   <li>"现有画像"占位</li>
     *   <li>"最近对话"占位</li>
     *   <li>"直接输出"约束 —— 阻止 LLM 加 markdown / 前后缀</li>
     * </ul>
     */
    static final String SYSTEM_PROMPT_TEMPLATE = """
            你是用户画像维护器。下面是用户和「小婉」的对话，以及这位用户的现有画像。
            请基于新的对话内容，更新画像段落。

            规则：
            1. 保留所有已知的稳定事实（姓名、职业、家乡等）
            2. 新增对话里能推断出的关于用户的信息
            3. 删除被新信息明确否定的旧内容
            4. 当用户透露的事实发生变化时（如换工作、搬家、新偏好），用"曾经/现在"或"原本/后来"等措辞显式标注时序，不要直接覆盖旧事实
            5. 用第三人称客观陈述，100-300 字
            6. 直接输出更新后的画像段落，不要任何前后缀

            现有画像：
            %s

            最近对话：
            %s

            更新后的画像：
            """;

    /** 现有画像为空时占位文本，避免给 LLM 看到 "现有画像: " 后空白。 */
    private static final String EMPTY_PROFILE_PLACEHOLDER = "（暂无）";

    private final MemoryProfileRepository repository;
    private final LLMProviderRouter llmRouter;

    /**
     * 基于 {@code recentMessages} 刷新 (userId, characterId) 对应的画像。
     *
     * <p>容错语义：
     * <ul>
     *   <li>{@code recentMessages} 为 null / 空 → 返回 null（无内容可学习）</li>
     *   <li>LLM 调用失败 / 返回 blank → 返回 null（不更新数据库，等下次再触发）</li>
     *   <li>乐观锁冲突重试耗尽 → 抛 {@link BusinessException}</li>
     * </ul>
     *
     * @return 刷新后的 entity；LLM 失败 / 无可学习内容时返回 {@code null}
     */
    public MemoryProfileEntity refresh(
            Long userId, Long characterId, List<MessageEntity> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return null;
        }
        return refreshWithRetry(userId, characterId, recentMessages, MAX_OPTIMISTIC_LOCK_RETRIES);
    }

    private MemoryProfileEntity refreshWithRetry(
            Long userId,
            Long characterId,
            List<MessageEntity> recentMessages,
            int retriesLeft) {
        try {
            MemoryProfileEntity entity = repository
                    .findByUserIdAndCharacterId(userId, characterId)
                    .orElseGet(() -> createInitialProfile(userId, characterId));

            String currentSummary = entity.getSummaryText() == null
                    ? "" : entity.getSummaryText();
            String newSummary = callLlmForUpdatedSummary(currentSummary, recentMessages);
            if (newSummary == null || newSummary.isBlank()) {
                return null;
            }

            entity.setSummaryText(newSummary.trim());
            return repository.save(entity);
        } catch (OptimisticLockingFailureException e) {
            if (retriesLeft <= 0) {
                log.error("Profile refresh 冲突重试耗尽 user={} char={}", userId, characterId, e);
                throw new BusinessException(MemoryErrCode.PROFILE_REFRESH_CONFLICT);
            }
            log.warn("Profile refresh 乐观锁冲突，重试 (剩 {} 次): user={} char={}",
                    retriesLeft, userId, characterId);
            return refreshWithRetry(userId, characterId, recentMessages, retriesLeft - 1);
        }
    }

    /**
     * 调 LLM 拿更新后的画像段落。失败 / 返回 blank 都返回 null，不抛 BusinessException——
     * 让上层 listener 跳过即可。
     */
    private String callLlmForUpdatedSummary(
            String currentSummary, List<MessageEntity> recentMessages) {
        String systemPrompt = String.format(
                SYSTEM_PROMPT_TEMPLATE,
                currentSummary.isBlank() ? EMPTY_PROFILE_PLACEHOLDER : currentSummary,
                formatMessagesForPrompt(recentMessages));

        List<Map<String, String>> openAiMessages = new ArrayList<>();
        openAiMessages.add(Map.of("role", "system", "content", systemPrompt));

        try {
            return llmRouter.chat(LLMTaskType.BACKGROUND, openAiMessages);
        } catch (BusinessException e) {
            log.warn("Profile refresh LLM 调用失败，跳过: errCode={}, msg={}",
                    e.getErrCode().getCode(), e.getMessage());
            return null;
        }
    }

    /**
     * 把消息列表拼成 LLM 可读的 user/assistant 段落。和 {@code PromptBuilder} 区别：
     * PromptBuilder 输出 OpenAI 兼容的 message 数组（多段独立 system/user/assistant 消息）；
     * 这里要把整段对话塞进单条 system prompt，所以走纯文本拼接。
     */
    private static String formatMessagesForPrompt(List<MessageEntity> messages) {
        StringBuilder sb = new StringBuilder();
        for (MessageEntity m : messages) {
            String role = SenderType.USER.equals(m.getSenderType()) ? "用户" : "小婉";
            String content = m.getContent() == null ? "" : m.getContent();
            sb.append(role).append("：").append(content).append("\n");
        }
        return sb.toString().trim();
    }

    private static MemoryProfileEntity createInitialProfile(Long userId, Long characterId) {
        MemoryProfileEntity e = new MemoryProfileEntity();
        e.setUserId(userId);
        e.setCharacterId(characterId);
        e.setSummaryText("");
        return e;
    }
}
