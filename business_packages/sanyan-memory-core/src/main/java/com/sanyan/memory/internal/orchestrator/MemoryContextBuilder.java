package com.sanyan.memory.internal.orchestrator;

import com.sanyan.memory.dto.MemoryContext;
import com.sanyan.memory.dto.MemoryFragment;
import com.sanyan.memory.internal.profile.MemoryProfileRepository;
import com.sanyan.memory.internal.rag.MemoryRagSearchService;
import com.sanyan.memory.internal.summary.MemorySummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Plan 2 Task Q1：长期记忆三层整合编排器（Plan 2.5 适配 free-form summary）。
 *
 * <p>把三条长期记忆通道整合成一段拼好的纯文本，下游 {@code PromptBuilder}（Q3）作为
 * 「她对你的记忆」段塞进 system prompt，介于角色 basePrompt 与短期对话窗口之间：
 *
 * <pre>
 *   summary_text（自然语言画像）  ─┐
 *   memory_summaries（最新一段）   ├─►  MemoryContextBuilder ─►  MemoryContext.text  ─►  PromptBuilder
 *   chat_embeddings（RAG Top K）  ─┘
 * </pre>
 *
 * <h2>三段顺序（spec §Q1 / 测试用例约定）</h2>
 * <ol>
 *   <li>profile：「她记得的关于你的事」—— LLM 直接维护的自然语言画像，最稳定的长期记忆</li>
 *   <li>summary：「最近的对话纪要」—— 跨越短期窗口的中期上下文</li>
 *   <li>RAG：「相关历史片段」—— 语义检索召回的远程历史</li>
 * </ol>
 * 顺序固定，调用方不需要关心拼接细节。
 *
 * <h2>空数据约定</h2>
 * <p>三层都没有可用数据时返回 {@code null}，让 PromptBuilder 直接跳过 system 消息注入（不要
 * 拼一个空 "【她对你的记忆】" 占位段污染 prompt）。任一段有数据就返回非 null 的
 * {@link MemoryContext}。
 *
 * <h2>降级</h2>
 * <p>RAG 不可用时（远程 embedding server 挂掉）由 {@link MemoryRagSearchService} 内部降级
 * 返回空 list（已打 ERROR 日志），本编排器只看到"RAG 段为空"，照常继续拼 profile + summary。
 * 主对话不受影响。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MemoryContextBuilder {

    private static final String SECTION_PROFILE_TITLE = "【她记得的关于你的事】";
    private static final String SECTION_SUMMARY_TITLE = "【最近的对话纪要】";
    private static final String SECTION_RAG_TITLE = "【相关历史片段】";

    private final MemorySummaryRepository summaryRepository;
    private final MemoryProfileRepository profileRepository;
    private final MemoryRagSearchService ragSearchService;

    /**
     * 组合三层长期记忆。
     *
     * @param userId      用户 id
     * @param characterId 角色 id（同一用户对不同角色的记忆隔离）
     * @param queryText   查询文本，通常是用户当前发的消息（仅 RAG 段使用）
     * @return 拼好的上下文；三层皆空时返回 {@code null}
     */
    public MemoryContext build(Long userId, Long characterId, String queryText) {
        StringBuilder sb = new StringBuilder();

        // ── profile 段：「她记得的关于你的事」 ──
        // Plan 2.5: summary_text 已是 LLM 维护的自然语言画像段落，blank 时跳过；不再做结构化格式化。
        profileRepository.findByUserIdAndCharacterId(userId, characterId)
                .map(MemoryProfileEntityAccessor::summaryTextOrNull)
                .filter(text -> text != null && !text.isBlank())
                .ifPresent(text -> sb.append(SECTION_PROFILE_TITLE).append("\n")
                        .append(text).append("\n\n"));

        // ── summary 段：最新一段「最近的对话纪要」 ──
        summaryRepository
                .findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(userId, characterId)
                .ifPresent(s -> sb.append(SECTION_SUMMARY_TITLE).append("\n")
                        .append(s.getSummaryText()).append("\n\n"));

        // ── RAG 段：相关历史片段（embedding 不可用时已在 RagSearchService 内降级为空列表） ──
        List<MemoryFragment> fragments = ragSearchService.search(userId, characterId, queryText);
        if (!fragments.isEmpty()) {
            sb.append(SECTION_RAG_TITLE).append("\n");
            for (MemoryFragment f : fragments) {
                // 把换行换成空格再列项，让"- " 列表始终单行，避免污染 LLM 看到的结构
                sb.append("- ").append(f.chunkText().replace("\n", " ")).append("\n");
            }
        }

        if (sb.length() == 0) {
            return null;
        }
        return new MemoryContext(sb.toString().trim());
    }

    /**
     * 为 {@code Optional.map} 提供方法引用入口的小帮手（避免 lambda 嵌套 null 处理）。
     * 抽出来纯粹是为了让上面的链式 {@code .map(...).filter(...).ifPresent(...)} 阅读清晰。
     */
    private static final class MemoryProfileEntityAccessor {
        private MemoryProfileEntityAccessor() {}
        static String summaryTextOrNull(com.sanyan.memory.internal.profile.MemoryProfileEntity p) {
            return p == null ? null : p.getSummaryText();
        }
    }
}
