package com.sanyan.memory.internal.orchestrator;

import com.sanyan.memory.dto.MemoryContext;
import com.sanyan.memory.dto.MemoryFragment;
import com.sanyan.memory.internal.profile.MemoryProfileEntity;
import com.sanyan.memory.internal.profile.MemoryProfileRepository;
import com.sanyan.memory.internal.profile.fixtures.MemoryProfileTestFixtures;
import com.sanyan.memory.internal.rag.MemoryRagSearchService;
import com.sanyan.memory.internal.summary.MemorySummaryEntity;
import com.sanyan.memory.internal.summary.MemorySummaryRepository;
import com.sanyan.memory.internal.summary.fixtures.MemorySummaryTestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Plan 2 Task Q1：MemoryContextBuilder 单元测试。
 *
 * <p>覆盖五个核心组合场景（spec §Q1 TDD 列表）：
 * <ol>
 *   <li>summary / profile / RAG 三者都没数据 → 返回 {@code null}</li>
 *   <li>仅有 summary → 输出只含"最近的对话纪要"段</li>
 *   <li>仅有 profile → 输出只含"她记得的关于你的事"段</li>
 *   <li>仅有 RAG → 输出只含"相关历史片段"段</li>
 *   <li>三者都有 → 完整组合，顺序：profile → summary → RAG</li>
 * </ol>
 *
 * <p>Mockito 单测，所有外部依赖 mock 掉，纯验证编排逻辑与文本输出格式。
 */
@ExtendWith(MockitoExtension.class)
class MemoryContextBuilderTest {

    private static final Long USER_ID = 1L;
    private static final Long CHARACTER_ID = 1L;
    private static final String QUERY_TEXT = "你好";

    @Mock
    MemorySummaryRepository summaryRepository;

    @Mock
    MemoryProfileRepository profileRepository;

    @Mock
    MemoryRagSearchService ragSearchService;

    @InjectMocks
    MemoryContextBuilder builder;

    @Test
    @DisplayName("三者都没数据 → 返回 null（让调用方跳过 context 注入）")
    void build_returnsNullWhenAllSourcesEmpty() {
        when(profileRepository.findByUserIdAndCharacterId(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.empty());
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.empty());
        when(ragSearchService.search(eq(USER_ID), eq(CHARACTER_ID), any()))
                .thenReturn(List.of());

        MemoryContext context = builder.build(USER_ID, CHARACTER_ID, QUERY_TEXT);

        assertThat(context).isNull();
    }

    @Test
    @DisplayName("只有 summary → context 仅含 summary 段")
    void build_withOnlySummary_emitsSummarySectionOnly() {
        MemorySummaryEntity summary = MemorySummaryTestFixtures.validSummary();
        summary.setSummaryText("最近聊了猫和工作");

        when(profileRepository.findByUserIdAndCharacterId(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.empty());
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.of(summary));
        when(ragSearchService.search(eq(USER_ID), eq(CHARACTER_ID), any()))
                .thenReturn(List.of());

        MemoryContext context = builder.build(USER_ID, CHARACTER_ID, QUERY_TEXT);

        assertThat(context).isNotNull();
        assertThat(context.text())
                .contains("【最近的对话纪要】")
                .contains("最近聊了猫和工作")
                .doesNotContain("【她记得的关于你的事】")
                .doesNotContain("【相关历史片段】");
    }

    @Test
    @DisplayName("只有 profile（basic_info + preferences + events）→ context 仅含 profile 段")
    void build_withOnlyProfile_emitsProfileSectionOnly() {
        MemoryProfileEntity profile = profileWithRichJsonb();

        when(profileRepository.findByUserIdAndCharacterId(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.of(profile));
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.empty());
        when(ragSearchService.search(eq(USER_ID), eq(CHARACTER_ID), any()))
                .thenReturn(List.of());

        MemoryContext context = builder.build(USER_ID, CHARACTER_ID, QUERY_TEXT);

        assertThat(context).isNotNull();
        assertThat(context.text())
                .contains("【她记得的关于你的事】")
                .contains("叫小明")
                .contains("28岁")
                .contains("从事工程师")
                .contains("喜欢吃")
                .contains("火锅")
                .contains("最近发生：")
                .contains("领养了一只猫")
                .doesNotContain("【最近的对话纪要】")
                .doesNotContain("【相关历史片段】");
    }

    @Test
    @DisplayName("profile 存在但 JSONB 全空 → 不输出 profile 段")
    void build_profileWithEmptyJsonb_doesNotEmitProfileSection() {
        // validProfile 的 JSONB 是默认空 schema（basic_info 全 null / 列表全空）
        MemoryProfileEntity profile = MemoryProfileTestFixtures.validProfile();

        when(profileRepository.findByUserIdAndCharacterId(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.of(profile));
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.empty());
        when(ragSearchService.search(eq(USER_ID), eq(CHARACTER_ID), any()))
                .thenReturn(List.of());

        MemoryContext context = builder.build(USER_ID, CHARACTER_ID, QUERY_TEXT);

        // profile 实体存在但 JSONB 内容全是默认空值，跳过该段；三段都空 → null
        assertThat(context).isNull();
    }

    @Test
    @DisplayName("只有 RAG → context 仅含相关历史片段段")
    void build_withOnlyRag_emitsRagSectionOnly() {
        List<MemoryFragment> fragments = List.of(
                new MemoryFragment("用户：我家有只猫\nAI：好可爱呀", Instant.parse("2026-04-01T00:00:00Z"), 0.85),
                new MemoryFragment("用户：今天加班到很晚\nAI：辛苦了", Instant.parse("2026-04-15T00:00:00Z"), 0.72)
        );

        when(profileRepository.findByUserIdAndCharacterId(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.empty());
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.empty());
        when(ragSearchService.search(eq(USER_ID), eq(CHARACTER_ID), any()))
                .thenReturn(fragments);

        MemoryContext context = builder.build(USER_ID, CHARACTER_ID, QUERY_TEXT);

        assertThat(context).isNotNull();
        assertThat(context.text())
                .contains("【相关历史片段】")
                .contains("用户：我家有只猫 AI：好可爱呀") // 换行被替换为空格
                .contains("用户：今天加班到很晚 AI：辛苦了")
                .doesNotContain("【她记得的关于你的事】")
                .doesNotContain("【最近的对话纪要】");
    }

    @Test
    @DisplayName("三者都有 → 完整组合，顺序：profile → summary → RAG")
    void build_withAllSources_emitsCompositeInExpectedOrder() {
        MemoryProfileEntity profile = profileWithRichJsonb();
        MemorySummaryEntity summary = MemorySummaryTestFixtures.validSummary();
        summary.setSummaryText("最近聊了猫和工作");
        List<MemoryFragment> fragments = List.of(
                new MemoryFragment("用户：我家有只猫\nAI：好可爱呀", Instant.now(), 0.85)
        );

        when(profileRepository.findByUserIdAndCharacterId(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.of(profile));
        when(summaryRepository.findFirstByUserIdAndCharacterIdOrderByCreatedAtDesc(USER_ID, CHARACTER_ID))
                .thenReturn(Optional.of(summary));
        when(ragSearchService.search(eq(USER_ID), eq(CHARACTER_ID), any()))
                .thenReturn(fragments);

        MemoryContext context = builder.build(USER_ID, CHARACTER_ID, QUERY_TEXT);

        assertThat(context).isNotNull();
        String text = context.text();

        // 三段都在
        assertThat(text)
                .contains("【她记得的关于你的事】")
                .contains("【最近的对话纪要】")
                .contains("【相关历史片段】");

        // 顺序：profile 在 summary 之前；summary 在 RAG 之前
        int profileIdx = text.indexOf("【她记得的关于你的事】");
        int summaryIdx = text.indexOf("【最近的对话纪要】");
        int ragIdx = text.indexOf("【相关历史片段】");
        assertThat(profileIdx).isLessThan(summaryIdx);
        assertThat(summaryIdx).isLessThan(ragIdx);
    }

    @Test
    @DisplayName("MemoryContext.isEmpty 判定空白文本")
    void memoryContext_isEmptyReflectsBlankText() {
        assertThat(new MemoryContext("").isEmpty()).isTrue();
        assertThat(new MemoryContext("   \n  ").isEmpty()).isTrue();
        assertThat(new MemoryContext("有内容").isEmpty()).isFalse();
        assertThat(MemoryContext.EMPTY.isEmpty()).isTrue();
    }

    /**
     * 构造一个内容丰富的 profile：basic_info 三项有值，preferences.foods 有数据，events 有一条。
     */
    @SuppressWarnings("unchecked")
    private static MemoryProfileEntity profileWithRichJsonb() {
        MemoryProfileEntity e = MemoryProfileTestFixtures.validProfile();
        Map<String, Object> jsonb = e.getProfileJsonb();

        Map<String, Object> basicInfo = (Map<String, Object>) jsonb.get("basic_info");
        basicInfo.put("name", "小明");
        basicInfo.put("age", 28);
        basicInfo.put("occupation", "工程师");

        Map<String, Object> preferences = (Map<String, Object>) jsonb.get("preferences");
        List<Object> foods = (List<Object>) preferences.get("foods");
        foods.add("火锅");
        foods.add("烧烤");

        List<Object> events = (List<Object>) jsonb.get("events");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("content", "领养了一只猫");
        event.put("occurred_at", "2026-04-01");
        events.add(event);

        return e;
    }
}
