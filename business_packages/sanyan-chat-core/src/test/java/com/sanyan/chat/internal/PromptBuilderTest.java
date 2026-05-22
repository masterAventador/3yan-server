package com.sanyan.chat.internal;

import com.sanyan.chat.SenderType;
import com.sanyan.memory.MemoryConstants;
import com.sanyan.memory.dto.MemoryContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 2 Task Q3：{@link PromptBuilder} 单元测试。
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>characterPrompt 作为第一条 system 消息</li>
 *   <li>memoryContext 非空时作为第二条 system 消息（含「她对你的记忆：」前缀）</li>
 *   <li>memoryContext 为 null / EMPTY 时跳过该 system 段</li>
 *   <li>消息历史按 SenderType 映射为 user / assistant role</li>
 *   <li>消息历史最多 {@link MemoryConstants#SHORT_TERM_WINDOW_SIZE} 条（= 32）</li>
 *   <li>传入超过 32 条时只取最近 32 条（按列表尾部）</li>
 *   <li>null content 兜底为空串（防止 NPE）</li>
 * </ul>
 */
class PromptBuilderTest {

    private final PromptBuilder builder = new PromptBuilder();

    @Test
    void build_shouldEmitCharacterPromptAsFirstSystemMessage() {
        List<Map<String, String>> result =
                builder.build("你是小婉", null, null, List.of());

        assertThat(result).isNotEmpty();
        assertThat(result.get(0))
                .containsEntry("role", "system")
                .containsEntry("content", "你是小婉");
    }

    @Test
    void build_shouldEmitMemoryContextAsSecondSystemMessageWhenPresent() {
        MemoryContext ctx = new MemoryContext("用户喜欢猫\n上次聊到 RAG");

        List<Map<String, String>> result =
                builder.build("你是小婉", null, ctx, List.of());

        assertThat(result).hasSize(2);
        assertThat(result.get(1))
                .containsEntry("role", "system");
        assertThat(result.get(1).get("content"))
                .startsWith("她对你的记忆：")
                .contains("用户喜欢猫")
                .contains("上次聊到 RAG");
    }

    @Test
    void build_shouldSkipMemoryContextWhenNull() {
        List<Map<String, String>> result =
                builder.build("你是小婉", null, null, List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("role", "system");
    }

    @Test
    void build_shouldSkipMemoryContextWhenEmpty() {
        List<Map<String, String>> result =
                builder.build("你是小婉", null, MemoryContext.EMPTY, List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("role", "system");
    }

    @Test
    void build_shouldSkipMemoryContextWhenBlankText() {
        MemoryContext ctx = new MemoryContext("   \n  ");

        List<Map<String, String>> result =
                builder.build("你是小婉", null, ctx, List.of());

        assertThat(result).hasSize(1);
    }

    @Test
    void build_shouldMapUserSenderTypeToUserRole() {
        MessageEntity msg = userMessage("早上好");

        List<Map<String, String>> result =
                builder.build("sys", null, null, List.of(msg));

        assertThat(result).hasSize(2);
        assertThat(result.get(1))
                .containsEntry("role", "user")
                .containsEntry("content", "早上好");
    }

    @Test
    void build_shouldMapAiSenderTypeToAssistantRole() {
        MessageEntity msg = aiMessage("早上好呀");

        List<Map<String, String>> result =
                builder.build("sys", null, null, List.of(msg));

        assertThat(result).hasSize(2);
        assertThat(result.get(1))
                .containsEntry("role", "assistant")
                .containsEntry("content", "早上好呀");
    }

    @Test
    void build_shouldFallbackNullContentToEmptyString() {
        MessageEntity msg = new MessageEntity();
        msg.setSenderType(SenderType.USER);
        msg.setContent(null);

        List<Map<String, String>> result =
                builder.build("sys", null, null, List.of(msg));

        assertThat(result).hasSize(2);
        assertThat(result.get(1)).containsEntry("content", "");
    }

    @Test
    void build_shouldEmitFullPromptStructureWithCharacterAndMemoryAndHistory() {
        MemoryContext ctx = new MemoryContext("用户最近聊到工作");
        MessageEntity user = userMessage("今天工作累");
        MessageEntity ai = aiMessage("辛苦了");

        List<Map<String, String>> result =
                builder.build("你是小婉", null, ctx, List.of(user, ai));

        assertThat(result).hasSize(4);
        assertThat(result.get(0)).containsEntry("role", "system").containsEntry("content", "你是小婉");
        assertThat(result.get(1)).containsEntry("role", "system");
        assertThat(result.get(1).get("content")).startsWith("她对你的记忆：");
        assertThat(result.get(2)).containsEntry("role", "user").containsEntry("content", "今天工作累");
        assertThat(result.get(3)).containsEntry("role", "assistant").containsEntry("content", "辛苦了");
    }

    @Test
    void build_shouldIncludeAllMessagesWhenExactlyAtWindowSize() {
        List<MessageEntity> messages = new ArrayList<>();
        for (int i = 0; i < MemoryConstants.SHORT_TERM_WINDOW_SIZE; i++) {
            messages.add(userMessage("m" + i));
        }

        List<Map<String, String>> result =
                builder.build("sys", null, null, messages);

        // 1 system + 32 history = 33
        assertThat(result).hasSize(1 + MemoryConstants.SHORT_TERM_WINDOW_SIZE);
        assertThat(result.get(1)).containsEntry("content", "m0");
        assertThat(result.get(MemoryConstants.SHORT_TERM_WINDOW_SIZE))
                .containsEntry("content", "m" + (MemoryConstants.SHORT_TERM_WINDOW_SIZE - 1));
    }

    @Test
    void build_shouldKeepOnlyLastWindowSizeMessagesWhenExceedingLimit() {
        // 传入 33 条，只取最近 32 条（即丢弃最早 1 条）
        int extra = 1;
        int total = MemoryConstants.SHORT_TERM_WINDOW_SIZE + extra;
        List<MessageEntity> messages = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            messages.add(userMessage("m" + i));
        }

        List<Map<String, String>> result =
                builder.build("sys", null, null, messages);

        // 1 system + 32 history = 33
        assertThat(result).hasSize(1 + MemoryConstants.SHORT_TERM_WINDOW_SIZE);
        // 第 1 条（index 1）应该是 m1（丢弃了 m0）
        assertThat(result.get(1)).containsEntry("content", "m" + extra);
        // 最后一条仍是 m32（即 total - 1）
        assertThat(result.get(MemoryConstants.SHORT_TERM_WINDOW_SIZE))
                .containsEntry("content", "m" + (total - 1));
    }

    @Test
    void build_shouldTolerateNullMessagesList() {
        List<Map<String, String>> result =
                builder.build("sys", null, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("role", "system");
    }

    // ---------- Plan 3 I2：stagePromptSegment 新增场景 ----------

    @Test
    void build_shouldInsertStageSegmentBetweenCharacterAndMemory() {
        var result = builder.build(
                "你是小婉",
                "当前关系阶段：暧昧。称呼：宝。",
                MemoryContext.EMPTY,
                List.of()
        );

        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("content")).isEqualTo("你是小婉");
        assertThat(result.get(1).get("content")).startsWith("当前关系阶段：暧昧");
    }

    @Test
    void build_shouldPlaceStageSegmentBeforeMemory() {
        MemoryContext ctx = new MemoryContext("用户喜欢编程");

        var result = builder.build(
                "你是小婉",
                "当前关系阶段：热恋。称呼：亲爱的。",
                ctx,
                List.of()
        );

        assertThat(result).hasSize(3);
        assertThat(result.get(0).get("content")).isEqualTo("你是小婉");
        assertThat(result.get(1).get("content")).startsWith("当前关系阶段：热恋");
        assertThat(result.get(2).get("content")).startsWith("她对你的记忆：");
    }

    @Test
    void build_shouldSkipStageSegmentWhenNull() {
        var result = builder.build("你是小婉", null, MemoryContext.EMPTY, List.of());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("content")).isEqualTo("你是小婉");
    }

    @Test
    void build_shouldSkipStageSegmentWhenBlank() {
        var result1 = builder.build("你是小婉", "", MemoryContext.EMPTY, List.of());
        assertThat(result1).hasSize(1);

        var result2 = builder.build("你是小婉", "   ", MemoryContext.EMPTY, List.of());
        assertThat(result2).hasSize(1);
    }

    @Test
    void build_shouldPrependTimestampToEachHistoryMessage() {
        // 每条历史消息内容前应有 [M月d日 HH:mm] 时间前缀，让 LLM 知道消息发生时间，
        // 回复时能用准确时间词（'刚才'/'昨天'/'前天'）而非默认"刚刚"。
        // 真实场景：前天聊机器人，今天再问"科幻新思路是啥"，AI 不应说"刚聊的"。
        LocalDateTime t1 = LocalDateTime.of(2026, 5, 21, 14, 30);
        LocalDateTime t2 = LocalDateTime.of(2026, 5, 23, 0, 18);
        MessageEntity older = userMessage("我想写科幻小说");
        older.setCreatedAt(t1);
        MessageEntity newer = userMessage("那你说的科幻新思路是啥");
        newer.setCreatedAt(t2);

        List<Map<String, String>> result =
                builder.build("你是小婉", null, null, List.of(older, newer));

        assertThat(result.get(1).get("content"))
                .as("第一条 history 应带 2026-05-21 14:30 的时间 prefix")
                .startsWith("[5月21日 14:30] ")
                .endsWith("我想写科幻小说");
        assertThat(result.get(2).get("content"))
                .as("第二条 history 应带 2026-05-23 00:18 的时间 prefix")
                .startsWith("[5月23日 00:18] ")
                .endsWith("那你说的科幻新思路是啥");
    }

    @Test
    void build_shouldNotPrependTimestampWhenCreatedAtIsNull() {
        // createdAt 兜底 null（罕见但要防 NPE）：不加 prefix，保留原内容。
        MessageEntity msg = userMessage("没时间戳的历史");
        // createdAt 默认 null

        List<Map<String, String>> result =
                builder.build("你是小婉", null, null, List.of(msg));

        assertThat(result.get(1).get("content"))
                .isEqualTo("没时间戳的历史");
    }

    private static MessageEntity userMessage(String content) {
        MessageEntity m = new MessageEntity();
        m.setSenderType(SenderType.USER);
        m.setContent(content);
        return m;
    }

    private static MessageEntity aiMessage(String content) {
        MessageEntity m = new MessageEntity();
        m.setSenderType(SenderType.AI);
        m.setContent(content);
        return m;
    }
}
