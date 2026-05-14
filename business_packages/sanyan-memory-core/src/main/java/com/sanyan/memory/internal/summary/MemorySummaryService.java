package com.sanyan.memory.internal.summary;

import com.sanyan.chat.internal.MessageEntity;
import com.sanyan.llm.internal.LLMProviderRouter;
import com.sanyan.llm.internal.LLMTaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Plan 2 Task N2：把一段对话历史压成"对话纪要"。
 *
 * <p>调用 {@link LLMProviderRouter} 并把任务类型钉死为 {@link LLMTaskType#BACKGROUND}——
 * 摘要属于后台异步任务，用户看不到原始输出，路由到 DeepSeek V4-Flash（低成本 + 大吞吐）。
 *
 * <p>system prompt 模板的硬约束：
 * <ul>
 *   <li>角色名「小婉」——产品定义的 AI 名称（旧名"林小满"已废弃）</li>
 *   <li>输出形式词「对话纪要」——产品定义的输出形式，给后续 prompt 拼装/UI 展示统一术语</li>
 *   <li>长度「100-200 字」——硬约束，太长会撑爆 prompt context 预算，太短信息量不够</li>
 * </ul>
 * 这些约束通过 {@code MemorySummaryServiceTest} 强制守护，prompt 改动出错时测试要 catch 住。
 *
 * <p>service 本身不对 LLM 输出做加工——LLM 返回什么就写什么进 memory_summaries.summary_text。
 * 后续 Phase N3（SummaryScheduler）调本 service 然后写库。
 *
 * <p>messages 列表也不在本层做任何加工，直接交给 router——router 内部 buildOpenAiMessages
 * 负责组装成 OpenAI 兼容的 {@code [{role, content}, ...]}，组装逻辑集中在 router 这一处。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemorySummaryService {

    /**
     * System prompt 模板。
     *
     * <p>用 Java 17 text block 保留多行可读性；硬约束词（小婉 / 对话纪要 / 100-200 字）必须
     * 出现在文本里，由单测兜底。
     */
    static final String SYSTEM_PROMPT = """
            你是一个对话摘要器。下面是用户和「小婉」的一段对话，请你提炼成一段简洁的"对话纪要"，
            保留关键事实、情感线索、用户提到的人事物。长度控制在 100-200 字。
            """;

    private final LLMProviderRouter llmRouter;

    /**
     * 对传入的对话历史生成一段"对话纪要"文本。
     *
     * @param messages 用户与 AI 的对话列表（通常 30 条，{@link com.sanyan.memory.internal.MemoryConstants#SUMMARY_TRIGGER_THRESHOLD}）
     * @return LLM 生成的纪要文本，service 不做任何加工原样返回
     */
    public String summarize(List<MessageEntity> messages) {
        return llmRouter.chat(LLMTaskType.BACKGROUND, SYSTEM_PROMPT, messages);
    }
}
