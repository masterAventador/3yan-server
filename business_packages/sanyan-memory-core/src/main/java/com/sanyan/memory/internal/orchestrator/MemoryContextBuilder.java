package com.sanyan.memory.internal.orchestrator;

import com.sanyan.memory.dto.MemoryContext;
import com.sanyan.memory.dto.MemoryFragment;
import com.sanyan.memory.internal.profile.MemoryProfileRepository;
import com.sanyan.memory.internal.rag.MemoryRagSearchService;
import com.sanyan.memory.internal.summary.MemorySummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plan 2 Task Q1：长期记忆三层整合编排器。
 *
 * <p>把三条长期记忆通道整合成一段拼好的纯文本，下游 {@code PromptBuilder}（Q3）作为
 * 「她对你的记忆」段塞进 system prompt，介于角色 basePrompt 与短期对话窗口之间：
 *
 * <pre>
 *   profile_jsonb（结构化档案）  ─┐
 *   memory_summaries（最新一段）  ├─►  MemoryContextBuilder ─►  MemoryContext.text  ─►  PromptBuilder
 *   chat_embeddings（RAG Top K）─┘
 * </pre>
 *
 * <h2>三段顺序（spec §Q1 / 测试用例约定）</h2>
 * <ol>
 *   <li>profile：「她记得的关于你的事」—— 用户身份 + 偏好 + 大事件，最稳定的长期记忆</li>
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
        profileRepository.findByUserIdAndCharacterId(userId, characterId).ifPresent(profile -> {
            String profileText = formatProfile(profile.getProfileJsonb());
            if (!profileText.isBlank()) {
                sb.append(SECTION_PROFILE_TITLE).append("\n")
                        .append(profileText).append("\n\n");
            }
        });

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
     * profile JSONB → 纯文本格式化。
     *
     * <p>schema 见 V6 头注释 / {@code MemoryProfileTestFixtures.defaultEmptyJsonb}：
     * <ul>
     *   <li>{@code basic_info}：name / age / occupation / hometown，拼成「你叫XX，YY岁，从事ZZ，来自WW」</li>
     *   <li>{@code preferences}：foods / movies / colors / music，每种非空数组单独一行</li>
     *   <li>{@code events}：最近最多 5 条 events.content，前缀「最近发生：」</li>
     *   <li>{@code emotion_line}：（占位）—— Q1 范围内先不输出，等 O 模块抽取规则稳定再决定怎么呈现</li>
     * </ul>
     * 任一段全空就跳过；整个 profile 全空时返回空字符串，由调用方决定是否输出段头。
     */
    @SuppressWarnings("unchecked")
    private static String formatProfile(Map<String, Object> profile) {
        if (profile == null || profile.isEmpty()) {
            return "";
        }

        StringBuilder out = new StringBuilder();

        // basic_info
        Object basic = profile.get("basic_info");
        if (basic instanceof Map<?, ?> basicMap && !basicMap.isEmpty()) {
            List<String> parts = new ArrayList<>();
            if (basicMap.get("name") != null) {
                parts.add("叫" + basicMap.get("name"));
            }
            if (basicMap.get("age") != null) {
                parts.add(basicMap.get("age") + "岁");
            }
            if (basicMap.get("occupation") != null) {
                parts.add("从事" + basicMap.get("occupation"));
            }
            if (basicMap.get("hometown") != null) {
                parts.add("来自" + basicMap.get("hometown"));
            }
            if (!parts.isEmpty()) {
                out.append("你").append(String.join("，", parts)).append("。\n");
            }
        }

        // preferences
        Object prefs = profile.get("preferences");
        if (prefs instanceof Map<?, ?> prefsMap) {
            List<String> prefLines = new ArrayList<>();
            prefsMap.forEach((k, v) -> {
                if (v instanceof List<?> list && !list.isEmpty()) {
                    String label = switch (k.toString()) {
                        case "foods" -> "喜欢吃";
                        case "movies" -> "喜欢的电影";
                        case "colors" -> "喜欢的颜色";
                        case "music" -> "喜欢的音乐";
                        default -> k.toString();
                    };
                    List<String> values = list.stream().map(Object::toString).toList();
                    prefLines.add(label + "：" + String.join("、", values));
                }
            });
            if (!prefLines.isEmpty()) {
                out.append(String.join("\n", prefLines)).append("\n");
            }
        }

        // events（取最近最多 5 条）
        Object events = profile.get("events");
        if (events instanceof List<?> eventList && !eventList.isEmpty()) {
            int total = eventList.size();
            int max = Math.min(5, total);
            int start = total - max;
            List<String> eventLines = new ArrayList<>();
            for (int i = start; i < total; i++) {
                Object e = eventList.get(i);
                if (e instanceof Map<?, ?> em && em.get("content") != null) {
                    eventLines.add("- " + em.get("content"));
                }
            }
            if (!eventLines.isEmpty()) {
                out.append("最近发生：\n");
                for (String line : eventLines) {
                    out.append(line).append("\n");
                }
            }
        }

        return out.toString().trim();
    }
}
